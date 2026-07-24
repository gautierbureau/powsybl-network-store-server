/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powsybl.network.store.model.IdentifiableAttributes;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Streams a collection GET response (a {@code TopLevelDocument}) directly from a JDBC
 * {@link ResultSet}, without materializing attribute POJOs or the response document.
 *
 * <p>Wire-format equivalence with the POJO + Jackson databind path is obtained by
 * construction: the field order and the values of non-column and SQL-{@code NULL}
 * fields are taken from serializing a default attributes instance with the same
 * mapper that serves the POJO path, and JSON-typed columns are written back raw —
 * their content in the database is exactly what this mapper produced at write time.
 *
 * <p>Not thread-safe; create one instance per response.
 */
public class IdentifiableCollectionJsonWriter {

    private enum ColumnKind {
        STRING, BOOLEAN, INTEGER, DOUBLE, RAW_JSON
    }

    private record Column(String name, int sqlIndex, ColumnKind kind) {
    }

    /**
     * One serialized attribute field, in serializer order. {@code column} is null for
     * fields not backed by a table column; {@code defaultRaw} is the pre-serialized
     * value an unset field carries (null when NON_NULL inclusion omits it entirely).
     */
    private record FieldWriter(String name, Column column, String defaultRaw) {
    }

    private final ObjectMapper mapper;
    private final TableMapping tableMapping;
    // one entry per serialized attribute field, in the bean serializer's order, with
    // everything precomputed: SQL index and kind for column-backed fields, and the
    // pre-serialized default value an unset field carries. With the application's
    // NON_NULL inclusion, a default of null means the field is omitted — which is
    // exactly what the POJO path produces for a SQL-NULL column, since the setter is
    // skipped and the field keeps its (null) default.
    private final List<FieldWriter> fieldWriters = new ArrayList<>();

    public IdentifiableCollectionJsonWriter(ObjectMapper mapper, TableMapping tableMapping) {
        this.mapper = mapper;
        this.tableMapping = tableMapping;
        IdentifiableAttributes defaultInstance = tableMapping.getAttributesSupplier().get();
        ObjectNode defaults = (ObjectNode) mapper.valueToTree(defaultInstance);

        // column keys usually equal the serialized property name, but not always
        // ("identifiableShortCircuit" -> identifiableShortCircuitAttributes,
        // "fictitiousp0" -> fictitiousP0), so each column is resolved empirically: set
        // a sentinel through the column's own setter on a probe instance, serialize it
        // and see which property changed. Exactly one property must change, otherwise
        // the table is refused (and the endpoint falls back to the POJO path).
        Map<String, Column> columnsByField = new java.util.HashMap<>();
        int sqlIndex = 2; // first selected column is the id
        for (Map.Entry<String, ColumnMapping> e : tableMapping.getColumnsMapping().entrySet()) {
            String property = resolveSerializedProperty(mapper, tableMapping, e.getKey(), e.getValue(), defaults);
            columnsByField.put(property, new Column(property, sqlIndex++, kindOf(e.getValue())));
        }

        try {
            mapper.getSerializerProviderInstance()
                    .findValueSerializer(defaultInstance.getClass())
                    .properties()
                    .forEachRemaining(property -> {
                        String name = property.getName();
                        JsonNode defaultValue = defaults.get(name);
                        String defaultRaw;
                        try {
                            defaultRaw = defaultValue == null ? null : mapper.writeValueAsString(defaultValue);
                        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                        fieldWriters.add(new FieldWriter(name, columnsByField.get(name), defaultRaw));
                    });
        } catch (com.fasterxml.jackson.databind.JsonMappingException e) {
            throw new IllegalStateException("Cannot introspect serializer of " + defaultInstance.getClass(), e);
        }

        // every column must have landed on a serialized property, or responses would drop it
        for (String property : columnsByField.keySet()) {
            if (fieldWriters.stream().noneMatch(w -> property.equals(w.name()))) {
                throw new IllegalStateException("Column property " + property + " of table " + tableMapping.getTable()
                        + " is not a serialized field; streaming would drop it");
            }
        }
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
     * Writes the complete {@code TopLevelDocument} for the rows of {@code resultSet}.
     *
     * <p>{@code satelliteRawByField} carries per-request enrichment for fields whose
     * value comes from a satellite table rather than a column: serialized field name to
     * (equipment id to pre-serialized JSON value). Rows without an entry get the
     * field's default, mirroring the materializing path. Field names must be
     * serialized, non-column-backed properties of the attributes.
     *
     * @return the number of rows written into {@code data}
     */
    public int write(ResultSet resultSet, int variantNum, Integer limit,
                     Map<String, Map<String, String>> satelliteRawByField, OutputStream out) throws IOException, SQLException {
        for (String fieldName : satelliteRawByField.keySet()) {
            FieldWriter field = fieldWriters.stream().filter(w -> w.name().equals(fieldName)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Satellite field " + fieldName
                            + " is not a serialized property of table " + tableMapping.getTable()));
            if (field.column() != null) {
                throw new IllegalStateException("Satellite field " + fieldName + " of table "
                        + tableMapping.getTable() + " is column-backed");
            }
        }
        int totalCount = 0;
        try (JsonGenerator generator = mapper.getFactory().createGenerator(out)) {
            generator.writeStartObject();
            generator.writeArrayFieldStart("data");
            while (resultSet.next()) {
                totalCount++;
                if (limit != null && totalCount > limit) {
                    continue; // keep consuming rows: totalCount mirrors the POJO path meta
                }
                writeResource(generator, resultSet, variantNum, satelliteRawByField);
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
                               Map<String, Map<String, String>> satelliteRawByField) throws IOException, SQLException {
        String id = resultSet.getString(1);
        generator.writeStartObject();
        generator.writeStringField("type", tableMapping.getResourceType().name());
        generator.writeStringField("id", id);
        generator.writeNumberField("variantNum", variantNum);
        generator.writeObjectFieldStart("attributes");
        for (FieldWriter field : fieldWriters) {
            Column column = field.column();
            if (column == null) {
                Map<String, String> satelliteRaw = satelliteRawByField.get(field.name());
                String raw = satelliteRaw == null ? null : satelliteRaw.get(id);
                if (raw != null) {
                    generator.writeFieldName(field.name());
                    generator.writeRawValue(raw);
                } else {
                    // not column-backed and no satellite value: the default
                    writeDefault(generator, field);
                }
                continue;
            }
            switch (column.kind()) {
                case STRING -> {
                    String value = resultSet.getString(column.sqlIndex());
                    if (value != null) {
                        generator.writeStringField(field.name(), value);
                    } else {
                        writeDefault(generator, field);
                    }
                }
                case BOOLEAN -> {
                    boolean value = resultSet.getBoolean(column.sqlIndex());
                    if (!resultSet.wasNull()) {
                        generator.writeBooleanField(field.name(), value);
                    } else {
                        writeDefault(generator, field);
                    }
                }
                case INTEGER -> {
                    int value = resultSet.getInt(column.sqlIndex());
                    if (!resultSet.wasNull()) {
                        generator.writeNumberField(field.name(), value);
                    } else {
                        writeDefault(generator, field);
                    }
                }
                case DOUBLE -> {
                    double value = resultSet.getDouble(column.sqlIndex());
                    if (!resultSet.wasNull()) {
                        generator.writeNumberField(field.name(), value);
                    } else {
                        writeDefault(generator, field);
                    }
                }
                case RAW_JSON -> {
                    String value = resultSet.getString(column.sqlIndex());
                    if (value != null) {
                        generator.writeFieldName(field.name());
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

    private void writeDefault(JsonGenerator generator, FieldWriter field) throws IOException {
        // mirror the POJO path for an unset field: fields whose default serialization
        // is omitted (nullable, NON_NULL inclusion) are skipped; the others (primitive
        // defaults, empty extension map) write their pre-serialized default
        if (field.defaultRaw() != null) {
            generator.writeFieldName(field.name());
            generator.writeRawValue(field.defaultRaw());
        }
    }
}
