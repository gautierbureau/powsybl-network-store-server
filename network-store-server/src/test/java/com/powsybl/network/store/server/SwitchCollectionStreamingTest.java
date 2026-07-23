/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powsybl.iidm.network.SwitchKind;
import com.powsybl.network.store.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-diff test of the streamed switches collection endpoint: the streamed
 * response must be equivalent to serializing the materializing (POJO) path's
 * document with the application mapper — for full variants (streamed), partial
 * variants (fallback), the limit parameter and an empty collection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SwitchCollectionStreamingTest {

    private static final UUID NETWORK_UUID = UUID.fromString("3c4d5e6f-0000-0000-0000-000000000003");

    @Autowired
    private NetworkStoreRepository repository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TestRestTemplate restTemplate;
    @LocalServerPort
    private int port;

    @BeforeEach
    void seed() {
        repository.deleteNetwork(NETWORK_UUID);
        repository.createNetworks(List.of(Resource.networkBuilder()
                .id("streaming-test-network")
                .variantNum(0)
                .attributes(NetworkAttributes.builder()
                        .uuid(NETWORK_UUID)
                        .variantId("v0")
                        .fullVariantNum(-1)
                        .caseDate(ZonedDateTime.parse("2026-01-01T00:00:00.000Z"))
                        .build())
                .build()));

        // one switch of each shape: node-breaker with every optional field set,
        // bus-breaker with nodes null, and a minimal one
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("source", "test");
        properties.put("weird \"chars\"", "line\nbreak & unicode é");
        Map<String, String> aliasByType = new LinkedHashMap<>();
        aliasByType.put("CGMES.name", "alias1");
        Set<String> aliasesWithoutType = new LinkedHashSet<>();
        aliasesWithoutType.add("free-alias");

        Resource<SwitchAttributes> rich = Resource.switchBuilder()
                .id("sw-rich")
                .variantNum(0)
                .attributes(SwitchAttributes.builder()
                        .voltageLevelId("vl1")
                        .name("switch one")
                        .kind(SwitchKind.BREAKER)
                        .node1(1)
                        .node2(2)
                        .open(true)
                        .retained(true)
                        .fictitious(true)
                        .properties(properties)
                        .aliasByType(aliasByType)
                        .aliasesWithoutType(aliasesWithoutType)
                        .build())
                .build();
        Resource<SwitchAttributes> busBreaker = Resource.switchBuilder()
                .id("sw-bus")
                .variantNum(0)
                .attributes(SwitchAttributes.builder()
                        .voltageLevelId("vl2")
                        .kind(SwitchKind.DISCONNECTOR)
                        .bus1("bus1")
                        .bus2("bus2")
                        .open(false)
                        .retained(false)
                        .build())
                .build();
        Resource<SwitchAttributes> minimal = Resource.switchBuilder()
                .id("sw-min")
                .variantNum(0)
                .attributes(SwitchAttributes.builder()
                        .voltageLevelId("vl1")
                        .kind(SwitchKind.LOAD_BREAK_SWITCH)
                        .build())
                .build();
        repository.createSwitches(NETWORK_UUID, List.of(rich, busBreaker, minimal));
    }

    @AfterEach
    void tearDown() {
        repository.deleteNetwork(NETWORK_UUID);
    }

    private String get(String pathAndQuery) {
        return restTemplate.getForObject("http://localhost:" + port + "/v1/networks/" + NETWORK_UUID + pathAndQuery, String.class);
    }

    private String materializedDocument(int variantNum, Integer limit) throws JsonProcessingException {
        List<Resource<SwitchAttributes>> resources = repository.getSwitches(NETWORK_UUID, variantNum);
        List<Resource<SwitchAttributes>> limited = limit == null || resources.size() < limit
                ? resources : resources.subList(0, limit);
        TopLevelDocument<SwitchAttributes> document = TopLevelDocument.of(limited);
        document.addMeta("totalCount", Integer.toString(resources.size()));
        return objectMapper.writeValueAsString(document);
    }

    private void assertResponseEquals(String expected, String actual) throws JsonProcessingException {
        // the contract is semantic equality of the parsed documents; byte equality is
        // asserted too because the writer reproduces the serializer's field order —
        // if this second assertion ever becomes a maintenance burden it can be dropped
        assertEquals(objectMapper.readTree(expected), objectMapper.readTree(actual));
        assertEquals(expected, actual);
    }

    @Test
    void streamedFullVariantResponseMatchesMaterializedResponse() throws Exception {
        assertResponseEquals(materializedDocument(0, null), get("/0/switches"));
    }

    @Test
    void limitIsAppliedAndTotalCountIsPreserved() throws Exception {
        assertResponseEquals(materializedDocument(0, 2), get("/0/switches?limit=2"));
    }

    @Test
    void partialVariantFallsBackAndStaysEquivalent() throws Exception {
        repository.cloneNetworkVariant(NETWORK_UUID, 0, 1, "v1");
        // shadow one switch in the partial variant
        Resource<SwitchAttributes> updated = Resource.switchBuilder()
                .id("sw-min")
                .variantNum(1)
                .attributes(SwitchAttributes.builder()
                        .voltageLevelId("vl1")
                        .kind(SwitchKind.LOAD_BREAK_SWITCH)
                        .open(true)
                        .build())
                .build();
        repository.updateSwitches(NETWORK_UUID, List.of(updated));

        assertResponseEquals(materializedDocument(1, null), get("/1/switches"));
    }

    @Test
    void emptyCollectionStreamsAnEmptyDocument() throws Exception {
        repository.deleteSwitches(NETWORK_UUID, 0, List.of("sw-rich", "sw-bus", "sw-min"));
        assertResponseEquals(materializedDocument(0, null), get("/0/switches"));
    }
}
