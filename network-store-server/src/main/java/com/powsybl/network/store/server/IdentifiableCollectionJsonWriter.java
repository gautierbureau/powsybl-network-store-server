/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import com.powsybl.network.store.model.IdentifiableAttributes;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.powsybl.network.store.server.Utils.bindAttributes;

/**
 * Streams a collection GET response (a {@code TopLevelDocument}) directly from a JDBC
 * {@link ResultSet}, without materializing attribute POJOs or the response document.
 *
 * <p>Wire-format equivalence with the POJO + Jackson databind path is obtained by
 * construction: field order comes from the bean serializer, unset fields take the
 * value a default attributes instance serializes with, JSON-typed columns are written
 * back raw (their stored text is exactly what this mapper produced at write time),
 * and <em>composite</em> properties — several columns whose setters build one shared
 * object, like a generator's reactive limits or a shunt's model — are rebuilt per row
 * on a scratch attributes instance through the very same column setters, then emitted
 * with the bean serializer's own property writer.
 *
 * <p>Thread-safe after construction; one instance per table is cached and shared.
 */
public class IdentifiableCollectionJsonWriter {

    private enum ColumnKind {
        STRING, BOOLEAN, INTEGER, DOUBLE, RAW_JSON
    }

    private record Column(int sqlIndex, ColumnKind kind, ColumnMapping<?, ?, ?, ?, ?> mapping) {
    }

    /**
     * Per-request satellite-table enrichment of one serialized field.
     * Either raw pass-through values for a simple field ({@code rawById}, with
     * {@code omitOnMiss} telling whether a row without an entry omits the field —
     * matching setters that assign null on miss — or keeps its default), or
     * {@code applierById} mutators applied to the scratch instance of a composite
     * field before it is serialized (e.g. reactive capability curve points).
     */
    public record SatelliteField(Map<String, String> rawById, boolean omitOnMiss,
                                 Map<String, Consumer<IdentifiableAttributes>> applierById) {
        public static SatelliteField raw(Map<String, String> rawById, boolean omitOnMiss) {
            return new SatelliteField(rawById, omitOnMiss, null);
        }

        public static SatelliteField appliers(Map<String, Consumer<IdentifiableAttributes>> applierById) {
            return new SatelliteField(null, false, applierById);
        }
    }

    /**
     * One serialized attribute field, in serializer order. Simple fields are backed by
     * at most one column ({@code column}, null for non-column fields) and fall back to
     * {@code defaultRaw} (null = omitted). Composite fields ({@code members} non-null)
     * are rebuilt per row via their columns' setters and written by {@code property}.
     */
    private record FieldWriter(String name, SerializedString encodedName, Column column, char[] defaultRaw,
                               List<Column> members, BeanPropertyWriter property) {
    }

    // columns whose sentinel cannot be probed (interface-typed variant columns);
    // resolution is by construction of the write-side mapping lambdas
    private static final Map<String, String> PROPERTY_ALIASES = Map.of(
            "linearModel", "model",
            "nonLinearModel", "model");

    private final ObjectMapper mapper;
    private final TableMapping tableMapping;
    private final List<FieldWriter> fieldWriters = new ArrayList<>();

    public IdentifiableCollectionJsonWriter(ObjectMapper mapper, TableMapping tableMapping) {
        this.mapper = mapper;
        this.tableMapping = tableMapping;
        IdentifiableAttributes defaultInstance = tableMapping.getAttributesSupplier().get();
        ObjectNode defaults = (ObjectNode) mapper.valueToTree(defaultInstance);

        // resolve each column to its serialized property (probing, see below), keeping
        // insertion order so composite members are re-applied in mapping order
        Map<String, List<Column>> columnsByProperty = new LinkedHashMap<>();
        int sqlIndex = 2; // first selected column is the id
        for (Map.Entry<String, ColumnMapping> e : tableMapping.getColumnsMapping().entrySet()) {
            String property = PROPERTY_ALIASES.containsKey(e.getKey())
                    ? PROPERTY_ALIASES.get(e.getKey())
                    : resolveSerializedProperty(mapper, tableMapping, e.getKey(), e.getValue(), defaults);
            columnsByProperty.computeIfAbsent(property, p -> new ArrayList<>())
                    .add(new Column(sqlIndex++, kindOf(e.getValue()), e.getValue()));
        }

        try {
            mapper.getSerializerProviderInstance()
                    .findValueSerializer(defaultInstance.getClass())
                    .properties()
                    .forEachRemaining(property -> fieldWriters.add(toFieldWriter(property, columnsByProperty, defaults)));
        } catch (com.fasterxml.jackson.databind.JsonMappingException e) {
            throw new IllegalStateException("Cannot introspect serializer of " + defaultInstance.getClass(), e);
        }

        for (String property : columnsByProperty.keySet()) {
            if (fieldWriters.stream().noneMatch(w -> property.equals(w.name()))) {
                throw new IllegalStateException("Column property " + property + " of table " + tableMapping.getTable()
                        + " is not a serialized field; streaming would drop it");
            }
        }
    }

    private FieldWriter toFieldWriter(PropertyWriter property, Map<String, List<Column>> columnsByProperty, ObjectNode defaults) {
        String name = property.getName();
        JsonNode defaultValue = defaults.get(name);
        char[] defaultRaw;
        try {
            defaultRaw = defaultValue == null ? null : mapper.writeValueAsString(defaultValue).toCharArray();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new java.io.UncheckedIOException(e);
        }
        SerializedString encodedName = new SerializedString(name);
        List<Column> columns = columnsByProperty.get(name);
        if (columns != null && columns.size() > 1) {
            // several columns build one object through stateful setters: rebuilt per row
            if (!(property instanceof BeanPropertyWriter beanProperty)) {
                throw new IllegalStateException("Composite property " + name + " of table "
                        + tableMapping.getTable() + " has no bean property writer");
            }
            return new FieldWriter(name, encodedName, null, defaultRaw, columns, beanProperty);
        }
        return new FieldWriter(name, encodedName, columns == null ? null : columns.get(0), defaultRaw, null, null);
    }

    @SuppressWarnings("unchecked")
    private static String resolveSerializedProperty(ObjectMapper mapper, TableMapping tableMapping,
                                                    String columnName, ColumnMapping columnMapping, ObjectNode defaults) {
        // two probes: a non-null sentinel (distinguishes fields whose default is
        // null/false/NaN) and null (distinguishes fields default-initialized to a
        // non-null value like an empty list, which the first probe cannot move)
        List<String> changed = diffProbe(mapper, tableMapping, columnMapping, defaults, sentinelFor(columnMapping));
        if (changed.isEmpty()) {
            changed = diffProbe(mapper, tableMapping, columnMapping, defaults, null);
        }
        if (changed.size() != 1) {
            throw new IllegalStateException("Column " + columnName + " of table " + tableMapping.getTable()
                    + " maps to " + changed + " serialized properties; streaming needs exactly one");
        }
        return changed.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> diffProbe(ObjectMapper mapper, TableMapping tableMapping,
                                          ColumnMapping columnMapping, ObjectNode defaults, Object sentinel) {
        IdentifiableAttributes probe = tableMapping.getAttributesSupplier().get();
        columnMapping.set(probe, sentinel);
        ObjectNode probed = (ObjectNode) mapper.valueToTree(probe);
        List<String> changed = new ArrayList<>();
        probed.fieldNames().forEachRemaining(name -> {
            if (!probed.get(name).equals(defaults.get(name))) {
                changed.add(name);
            }
        });
        defaults.fieldNames().forEachRemaining(name -> {
            if (!probed.has(name)) {
                changed.add(name);
            }
        });
        return changed;
    }

    private static Object sentinelFor(ColumnMapping<?, ?, ?, ?, ?> columnMapping) {
        if (columnMapping.getClassMapKey() != null && columnMapping.getClassMapValue() != null) {
            return Map.of();
        }
        Class<?> classR = columnMapping.getClassR();
        if (classR == String.class) {
            return "streaming-probe";
        }
        if (classR == Boolean.class) {
            return Boolean.TRUE;
        }
        if (classR == Integer.class) {
            return 7;
        }
        if (classR == Double.class) {
            return 1.25;
        }
        if (classR.isEnum()) {
            return classR.getEnumConstants()[0];
        }
        if (java.util.Set.class.isAssignableFrom(classR)) {
            return java.util.Set.of();
        }
        if (List.class.isAssignableFrom(classR)) {
            return List.of();
        }
        if (Map.class.isAssignableFrom(classR)) {
            return Map.of();
        }
        try {
            return classR.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No sentinel for column type " + classR, e);
        }
    }

    /**
     * True when every column of the table can be streamed. Tables with column types
     * outside the supported set (e.g. {@code Instant}) must use the POJO path.
     */
    public static boolean supports(TableMapping tableMapping) {
        return tableMapping.getColumnsMapping().values().stream().allMatch(c -> kindOf(c) != null);
    }

    private static ColumnKind kindOf(ColumnMapping<?, ?, ?, ?, ?> columnMapping) {
        Class<?> classR = columnMapping.getClassR();
        if (classR == null) {
            return ColumnKind.RAW_JSON;
        }
        if (classR == String.class) {
            return ColumnKind.STRING;
        }
        if (classR == Boolean.class) {
            return ColumnKind.BOOLEAN;
        }
        if (classR == Integer.class) {
            return ColumnKind.INTEGER;
        }
        if (classR == Double.class) {
            return ColumnKind.DOUBLE;
        }
        if (java.time.Instant.class.equals(classR) || java.util.Date.class.isAssignableFrom(classR)
                || java.util.UUID.class.equals(classR) || classR == Long.class || classR == Float.class) {
            return null; // not needed by identifiable tables today; fall back rather than guess
        }
        // everything else is stored as a JSON string written by the same mapper
        return ColumnKind.RAW_JSON;
    }

    /**
     * Writes the complete {@code TopLevelDocument} for the rows of {@code resultSet};
     * see {@link SatelliteField} for the per-request enrichment contract.
     *
     * @return the number of rows written into {@code data}
     */
    public int write(ResultSet resultSet, int variantNum, Integer limit,
                     Map<String, SatelliteField> satelliteByField, OutputStream out) throws IOException, SQLException {
        for (Map.Entry<String, SatelliteField> e : satelliteByField.entrySet()) {
            FieldWriter field = fieldWriters.stream().filter(w -> w.name().equals(e.getKey())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Satellite field " + e.getKey()
                            + " is not a serialized property of table " + tableMapping.getTable()));
            boolean composite = field.members() != null;
            if (composite && e.getValue().applierById() == null || !composite && e.getValue().rawById() == null) {
                throw new IllegalStateException("Satellite field " + e.getKey() + " of table "
                        + tableMapping.getTable() + " has the wrong enrichment kind");
            }
            if (!composite && field.column() != null) {
                throw new IllegalStateException("Satellite field " + e.getKey() + " of table "
                        + tableMapping.getTable() + " is column-backed");
            }
        }
        SerializerProvider provider = mapper.getSerializerProviderInstance();
        int totalCount = 0;
        try (JsonGenerator generator = mapper.getFactory().createGenerator(out)) {
            generator.writeStartObject();
            generator.writeArrayFieldStart("data");
            while (resultSet.next()) {
                totalCount++;
                if (limit != null && totalCount > limit) {
                    continue; // keep consuming rows: totalCount mirrors the POJO path meta
                }
                writeResource(generator, resultSet, variantNum, satelliteByField, provider);
            }
            generator.writeEndArray();
            generator.writeObjectFieldStart("meta");
            generator.writeStringField("totalCount", Integer.toString(totalCount));
            generator.writeEndObject();
            generator.writeEndObject();
        }
        return limit == null ? totalCount : Math.min(totalCount, limit);
    }

    private void writeResource(JsonGenerator generator, ResultSet resultSet, int variantNum,
                               Map<String, SatelliteField> satelliteByField, SerializerProvider provider) throws IOException, SQLException {
        String id = resultSet.getString(1);
        generator.writeStartObject();
        generator.writeStringField("type", tableMapping.getResourceType().name());
        generator.writeStringField("id", id);
        generator.writeNumberField("variantNum", variantNum);
        generator.writeObjectFieldStart("attributes");
        for (FieldWriter field : fieldWriters) {
            if (field.members() != null) {
                writeComposite(generator, resultSet, field, satelliteByField.get(field.name()), id, provider);
                continue;
            }
            Column column = field.column();
            if (column == null) {
                SatelliteField satellite = satelliteByField.get(field.name());
                String raw = satellite == null ? null : satellite.rawById().get(id);
                if (raw != null) {
                    generator.writeFieldName(field.encodedName());
                    generator.writeRawValue(raw);
                } else if (satellite == null || !satellite.omitOnMiss()) {
                    writeDefault(generator, field);
                }
                continue;
            }
            switch (column.kind()) {
                case STRING -> {
                    String value = resultSet.getString(column.sqlIndex());
                    if (value != null) {
                        generator.writeFieldName(field.encodedName());
                        generator.writeString(value);
                    } else {
                        writeDefault(generator, field);
                    }
                }
                case BOOLEAN -> {
                    boolean value = resultSet.getBoolean(column.sqlIndex());
                    if (!resultSet.wasNull()) {
                        generator.writeFieldName(field.encodedName());
                        generator.writeBoolean(value);
                    } else {
                        writeDefault(generator, field);
                    }
                }
                case INTEGER -> {
                    int value = resultSet.getInt(column.sqlIndex());
                    if (!resultSet.wasNull()) {
                        generator.writeFieldName(field.encodedName());
                        generator.writeNumber(value);
                    } else {
                        writeDefault(generator, field);
                    }
                }
                case DOUBLE -> {
                    double value = resultSet.getDouble(column.sqlIndex());
                    if (!resultSet.wasNull()) {
                        generator.writeFieldName(field.encodedName());
                        generator.writeNumber(value);
                    } else {
                        writeDefault(generator, field);
                    }
                }
                case RAW_JSON -> {
                    String value = resultSet.getString(column.sqlIndex());
                    if (value != null) {
                        generator.writeFieldName(field.encodedName());
                        generator.writeRawValue(value);
                    } else {
                        writeDefault(generator, field);
                    }
                }
            }
        }
        generator.writeEndObject();
        generator.writeEndObject();
    }

    /**
     * Rebuilds a composite property exactly like the POJO path: fresh attributes,
     * the member columns' own setters in mapping order (null columns skipped, as in
     * {@code bindAttributes}), then the satellite applier, then the bean serializer's
     * property writer — which also enforces the same NON_NULL omission.
     */
    private void writeComposite(JsonGenerator generator, ResultSet resultSet, FieldWriter field,
                                SatelliteField satellite, String id, SerializerProvider provider) throws SQLException {
        IdentifiableAttributes scratch = tableMapping.getAttributesSupplier().get();
        for (Column member : field.members()) {
            bindAttributes(resultSet, member.sqlIndex(), member.mapping(), scratch, mapper);
        }
        if (satellite != null) {
            Consumer<IdentifiableAttributes> applier = satellite.applierById().get(id);
            if (applier != null) {
                applier.accept(scratch);
            }
        }
        try {
            field.property().serializeAsField(scratch, generator, provider);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize composite property " + field.name()
                    + " of table " + tableMapping.getTable(), e);
        }
    }

    private void writeDefault(JsonGenerator generator, FieldWriter field) throws IOException {
        // mirror the POJO path for an unset field: fields whose default serialization
        // is omitted (nullable, NON_NULL inclusion) are skipped; the others (primitive
        // defaults, empty extension map) write their pre-serialized default
        if (field.defaultRaw() != null) {
            generator.writeFieldName(field.encodedName());
            generator.writeRawValue(field.defaultRaw(), 0, field.defaultRaw().length);
        }
    }
}
