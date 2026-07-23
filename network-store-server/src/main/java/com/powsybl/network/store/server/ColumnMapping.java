/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import lombok.NonNull;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Column mapping.
 * @author Franck Lecuyer <franck.lecuyer at rte-france.com>
 */
public class ColumnMapping<T, R, U, K, O> {
    private Class<R> classR;
    private Function<T, R> getter;
    private BiConsumer<T, U> setter;
    private Class<K> classMapKey;
    private Class<O> classMapValue;
    // One ObjectReader per column, built on first use and reused for every row:
    // resolving the deserializer through ObjectMapper.readValue on every cell costs
    // more than the actual parsing for small JSON columns. Benign race: ObjectReader
    // is immutable and the mapper is a singleton, so concurrent first calls build
    // identical readers and the reference write is atomic.
    private ObjectReader reader;

    ColumnMapping(@NonNull Class<R> classR, @NonNull Function<T, R> getter, @NonNull BiConsumer<T, U> setter) {
        this(classR, getter, setter, null, null);
    }

    ColumnMapping(Class<R> classR, @NonNull Function<T, R> getter, @NonNull BiConsumer<T, U> setter, Class<K> classMapKey, Class<O> classMapValue) {
        this.classR = classR;
        this.getter = getter;
        this.setter = setter;
        this.classMapKey = classMapKey;
        this.classMapValue = classMapValue;
    }

    R get(T obj) {
        return getter.apply(obj);
    }

    void set(T obj, U value) {
        setter.accept(obj, value);
    }

    Class<R> getClassR() {
        return classR;
    }

    Class<K> getClassMapKey() {
        return classMapKey;
    }

    Class<O> getClassMapValue() {
        return classMapValue;
    }

    ObjectReader getReader(ObjectMapper mapper) {
        if (reader == null) {
            reader = classMapKey != null && classMapValue != null
                    ? mapper.readerFor(mapper.getTypeFactory().constructMapType(Map.class, classMapKey, classMapValue))
                    : mapper.readerFor(classR);
        }
        return reader;
    }
}
