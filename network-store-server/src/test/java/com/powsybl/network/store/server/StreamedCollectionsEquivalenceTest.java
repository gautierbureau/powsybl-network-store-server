/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powsybl.iidm.network.Country;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.TopologyKind;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-diff tests of the streamed satellite-free collection endpoints
 * (substations, voltage levels, tie lines, hvdc lines, grounds, configured
 * buses): each streamed response must be byte-identical to serializing the
 * materializing path's document with the application mapper. The switches
 * endpoint has its own richer test (SwitchCollectionStreamingTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamedCollectionsEquivalenceTest {

    private static final UUID NETWORK_UUID = UUID.fromString("4d5e6f70-0000-0000-0000-000000000004");

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
                .id("streamed-collections-network")
                .variantNum(0)
                .attributes(NetworkAttributes.builder()
                        .uuid(NETWORK_UUID)
                        .variantId("v0")
                        .fullVariantNum(-1)
                        .caseDate(ZonedDateTime.parse("2026-01-01T00:00:00.000Z"))
                        .build())
                .build()));

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("origin", "equivalence-test");

        repository.createSubstations(NETWORK_UUID, List.of(
                Resource.substationBuilder().id("s-rich").variantNum(0)
                        .attributes(SubstationAttributes.builder()
                                .name("substation one")
                                .country(Country.FR)
                                .tso("RTE")
                                .entsoeArea(EntsoeAreaAttributes.builder().code("D7").build())
                                .properties(properties)
                                .build())
                        .build(),
                Resource.substationBuilder().id("s-min").variantNum(0)
                        .attributes(SubstationAttributes.builder().build())
                        .build()));

        repository.createVoltageLevels(NETWORK_UUID, List.of(
                Resource.voltageLevelBuilder().id("vl-rich").variantNum(0)
                        .attributes(VoltageLevelAttributes.builder()
                                .substationId("s-rich")
                                .name("vl one")
                                .nominalV(380)
                                .lowVoltageLimit(360)
                                .highVoltageLimit(400)
                                .topologyKind(TopologyKind.NODE_BREAKER)
                                .internalConnections(List.of(
                                        InternalConnectionAttributes.builder().node1(10).node2(20).build(),
                                        InternalConnectionAttributes.builder().node1(11).node2(21).build()))
                                .properties(properties)
                                .build())
                        .build(),
                Resource.voltageLevelBuilder().id("vl-min").variantNum(0)
                        .attributes(VoltageLevelAttributes.builder()
                                .substationId("s-min")
                                .nominalV(225)
                                .topologyKind(TopologyKind.BUS_BREAKER)
                                .build())
                        .build()));

        repository.createTieLines(NETWORK_UUID, List.of(
                Resource.tieLineBuilder().id("tl-1").variantNum(0)
                        .attributes(TieLineAttributes.builder()
                                .name("tie line")
                                .boundaryLine1Id("bl1")
                                .boundaryLine2Id("bl2")
                                .build())
                        .build()));

        repository.createHvdcLines(NETWORK_UUID, List.of(
                Resource.hvdcLineBuilder().id("hvdc-1").variantNum(0)
                        .attributes(HvdcLineAttributes.builder()
                                .name("hvdc")
                                .r(0.5)
                                .convertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER)
                                .nominalV(400)
                                .activePowerSetpoint(500)
                                .maxP(1000)
                                .converterStationId1("cs1")
                                .converterStationId2("cs2")
                                .build())
                        .build()));

        repository.createGrounds(NETWORK_UUID, List.of(
                Resource.groundBuilder().id("gr-1").variantNum(0)
                        .attributes(GroundAttributes.builder()
                                .voltageLevelId("vl-rich")
                                .node(4)
                                .build())
                        .build()));

        repository.createBuses(NETWORK_UUID, List.of(
                Resource.configuredBusBuilder().id("cb-1").variantNum(0)
                        .attributes(ConfiguredBusAttributes.builder()
                                .voltageLevelId("vl-min")
                                .name("bus one")
                                .v(224.7)
                                .angle(1.5)
                                .build())
                        .build(),
                Resource.configuredBusBuilder().id("cb-2").variantNum(0)
                        .attributes(ConfiguredBusAttributes.builder()
                                .voltageLevelId("vl-min")
                                .build())
                        .build()));
    }

    @AfterEach
    void tearDown() {
        repository.deleteNetwork(NETWORK_UUID);
    }

    private <T extends IdentifiableAttributes> void assertStreamedEquals(String endpoint, Supplier<List<Resource<T>>> materializing) throws JsonProcessingException {
        String streamed = restTemplate.getForObject(
                "http://localhost:" + port + "/v1/networks/" + NETWORK_UUID + "/0/" + endpoint, String.class);
        List<Resource<T>> resources = materializing.get();
        TopLevelDocument<T> document = TopLevelDocument.of(resources);
        document.addMeta("totalCount", Integer.toString(resources.size()));
        String expected = objectMapper.writeValueAsString(document);
        assertEquals(objectMapper.readTree(expected), objectMapper.readTree(streamed));
        assertEquals(expected, streamed);
    }

    @Test
    void substations() throws Exception {
        assertStreamedEquals("substations", () -> repository.getSubstations(NETWORK_UUID, 0));
    }

    @Test
    void voltageLevels() throws Exception {
        assertStreamedEquals("voltage-levels", () -> repository.getVoltageLevels(NETWORK_UUID, 0));
    }

    @Test
    void tieLines() throws Exception {
        assertStreamedEquals("tie-lines", () -> repository.getTieLines(NETWORK_UUID, 0));
    }

    @Test
    void hvdcLines() throws Exception {
        assertStreamedEquals("hvdc-lines", () -> repository.getHvdcLines(NETWORK_UUID, 0));
    }

    @Test
    void grounds() throws Exception {
        assertStreamedEquals("grounds", () -> repository.getGrounds(NETWORK_UUID, 0));
    }

    @Test
    void configuredBuses() throws Exception {
        assertStreamedEquals("configured-buses", () -> repository.getConfiguredBuses(NETWORK_UUID, 0));
    }

    @Test
    void partialVariantFallsBackAndStaysEquivalent() throws Exception {
        repository.cloneNetworkVariant(NETWORK_UUID, 0, 1, "v1");
        String streamed = restTemplate.getForObject(
                "http://localhost:" + port + "/v1/networks/" + NETWORK_UUID + "/1/voltage-levels", String.class);
        List<Resource<VoltageLevelAttributes>> resources = repository.getVoltageLevels(NETWORK_UUID, 1);
        TopLevelDocument<VoltageLevelAttributes> document = TopLevelDocument.of(resources);
        document.addMeta("totalCount", Integer.toString(resources.size()));
        assertEquals(objectMapper.writeValueAsString(document), streamed);
    }
}
