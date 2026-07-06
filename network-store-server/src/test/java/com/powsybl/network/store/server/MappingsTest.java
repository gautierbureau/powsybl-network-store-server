/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Gautier Bureau <gautier.bureau at rte-france.com>
 */
class MappingsTest {

    @Test
    void allMappingsAreListedExactlyOnce() {
        Mappings mappings = new Mappings();

        // no table mapping must appear twice in the 'all' list, and every distinct resource type
        // must be represented exactly once
        Set<String> tables = new HashSet<>();
        Set<Object> resourceTypes = new HashSet<>();
        mappings.getAll().forEach(tableMapping -> {
            tables.add(tableMapping.getTable());
            resourceTypes.add(tableMapping.getResourceType());
        });
        assertEquals(mappings.getAll().size(), tables.size(), "a table mapping is listed more than once in Mappings.all");
        assertEquals(mappings.getAll().size(), resourceTypes.size(), "a resource type is listed more than once in Mappings.all");
    }
}
