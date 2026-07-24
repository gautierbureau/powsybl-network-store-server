/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powsybl.network.store.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-diff tests of the streamed collections that carry satellite-table
 * enrichment: loads, lines and busbar sections merge {@code regulatingEquipments}
 * from the regulatingpoint table. Streamed responses must be byte-identical to
 * the materializing path, both for regulated elements (non-empty sets, built and
 * serialized by the same code on both paths) and unregulated ones (default []).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamedRegulatedCollectionsEquivalenceTest {

    private static final UUID NETWORK_UUID = UUID.fromString("5e6f7081-0000-0000-0000-000000000005");

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
                .id("streamed-regulated-network")
                .variantNum(0)
                .attributes(NetworkAttributes.builder()
                        .uuid(NETWORK_UUID)
                        .variantId("v0")
                        .fullVariantNum(-1)
                        .caseDate(ZonedDateTime.parse("2026-01-01T00:00:00.000Z"))
                        .build())
                .build()));

        repository.createLoads(NETWORK_UUID, List.of(
                Resource.loadBuilder().id("load-regulated").variantNum(0)
                        .attributes(LoadAttributes.builder().voltageLevelId("vl1").p0(10).q0(2).build())
                        .build(),
                Resource.loadBuilder().id("load-plain").variantNum(0)
                        .attributes(LoadAttributes.builder().voltageLevelId("vl1").p0(20).build())
                        .build()));

        repository.createLines(NETWORK_UUID, List.of(
                Resource.lineBuilder().id("line-regulated").variantNum(0)
                        .attributes(LineAttributes.builder().voltageLevelId1("vl1").voltageLevelId2("vl2").r(0.1).x(1).build())
                        .build(),
                Resource.lineBuilder().id("line-plain").variantNum(0)
                        .attributes(LineAttributes.builder().voltageLevelId1("vl1").voltageLevelId2("vl2").build())
                        .build()));

        repository.createBusbarSections(NETWORK_UUID, List.of(
                Resource.busbarSectionBuilder().id("bbs-1").variantNum(0)
                        .attributes(BusbarSectionAttributes.builder().voltageLevelId("vl1").node(1).build())
                        .build()));

        // two generators regulating the same load, one regulating a line: the sets and
        // their serialization are produced by the same repository code on both paths
        repository.createGenerators(NETWORK_UUID, List.of(
                regulator("gen-1", ResourceType.LOAD, "load-regulated"),
                regulator("gen-2", ResourceType.LOAD, "load-regulated"),
                regulator("gen-3", ResourceType.LINE, "line-regulated")));
    }

    private static Resource<GeneratorAttributes> regulator(String id, ResourceType regulatedType, String regulatedId) {
        return Resource.generatorBuilder().id(id).variantNum(0)
                .attributes(GeneratorAttributes.builder()
                        .voltageLevelId("vl1")
                        .targetP(50)
                        .regulatingPoint(RegulatingPointAttributes.builder()
                                .regulatingEquipmentId(id)
                                .regulatedResourceType(regulatedType)
                                .localTerminal(TerminalRefAttributes.builder().connectableId(id).build())
                                .regulatingTerminal(TerminalRefAttributes.builder().connectableId(regulatedId).build())
                                .build())
                        .build())
                .build();
    }

    @AfterEach
    void tearDown() {
        repository.deleteNetwork(NETWORK_UUID);
    }

    private <T extends IdentifiableAttributes> void assertStreamedEquals(String endpoint, int variantNum, Supplier<List<Resource<T>>> materializing) throws JsonProcessingException {
        String streamed = restTemplate.getForObject(
                "http://localhost:" + port + "/v1/networks/" + NETWORK_UUID + "/" + variantNum + "/" + endpoint, String.class);
        List<Resource<T>> resources = materializing.get();
        TopLevelDocument<T> document = TopLevelDocument.of(resources);
        document.addMeta("totalCount", Integer.toString(resources.size()));
        String expected = objectMapper.writeValueAsString(document);
        assertEquals(objectMapper.readTree(expected), objectMapper.readTree(streamed));
        assertEquals(expected, streamed);
    }

    @Test
    void loadsWithRegulatingEquipments() throws Exception {
        // sanity: the regulated load really carries a non-empty enrichment
        assertTrue(repository.getLoads(NETWORK_UUID, 0).stream()
                .filter(l -> l.getId().equals("load-regulated"))
                .allMatch(l -> l.getAttributes().getRegulatingEquipments().size() == 2));
        assertStreamedEquals("loads", 0, () -> repository.getLoads(NETWORK_UUID, 0));
    }

    @Test
    void linesWithRegulatingEquipments() throws Exception {
        assertStreamedEquals("lines", 0, () -> repository.getLines(NETWORK_UUID, 0));
    }

    @Test
    void busbarSectionsWithoutRegulators() throws Exception {
        assertStreamedEquals("busbar-sections", 0, () -> repository.getBusbarSections(NETWORK_UUID, 0));
    }

    @Test
    void partialVariantFallsBackAndStaysEquivalent() throws Exception {
        repository.cloneNetworkVariant(NETWORK_UUID, 0, 1, "v1");
        assertStreamedEquals("loads", 1, () -> repository.getLoads(NETWORK_UUID, 1));
    }
}
