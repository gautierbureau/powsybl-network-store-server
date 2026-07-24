/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powsybl.iidm.network.EnergySource;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-diff tests of the streamed regulating-point-family collections
 * (generators, batteries, shunt compensators, static var compensators,
 * vsc/lcc converter stations). These exercise the composite-property writer
 * (reactive limits assembled from several columns plus satellite curve points,
 * shunt models stored in variant columns) and the omit-on-miss satellite
 * fields (regulating point, regulating equipments assigned null when absent).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamedRegulatingFamilyEquivalenceTest {

    private static final UUID NETWORK_UUID = UUID.fromString("6f708192-0000-0000-0000-000000000006");

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
                .id("streamed-regulating-family-network")
                .variantNum(0)
                .attributes(NetworkAttributes.builder()
                        .uuid(NETWORK_UUID)
                        .variantId("v0")
                        .fullVariantNum(-1)
                        .caseDate(ZonedDateTime.parse("2026-01-01T00:00:00.000Z"))
                        .build())
                .build()));

        TreeMap<Double, ReactiveCapabilityCurvePointAttributes> curve = new TreeMap<>();
        curve.put(-100.0, ReactiveCapabilityCurvePointAttributes.builder().p(-100.0).minQ(-40.0).maxQ(40.0).build());
        curve.put(0.0, ReactiveCapabilityCurvePointAttributes.builder().p(0.0).minQ(-50.0).maxQ(50.0).build());
        curve.put(100.0, ReactiveCapabilityCurvePointAttributes.builder().p(100.0).minQ(-30.0).maxQ(30.0).build());

        repository.createGenerators(NETWORK_UUID, List.of(
                Resource.generatorBuilder().id("gen-minmax").variantNum(0)
                        .attributes(GeneratorAttributes.builder()
                                .voltageLevelId("vl1")
                                .name("minmax generator")
                                .energySource(EnergySource.HYDRO)
                                .targetP(100)
                                .reactiveLimits(MinMaxReactiveLimitsAttributes.builder().minQ(-50).maxQ(50)
                                        .properties(Map.of("origin", "test")).build())
                                .regulatingPoint(regulatingPoint("gen-minmax", ResourceType.GENERATOR, "gen-minmax"))
                                .build())
                        .build(),
                Resource.generatorBuilder().id("gen-curve").variantNum(0)
                        .attributes(GeneratorAttributes.builder()
                                .voltageLevelId("vl1")
                                .targetP(200)
                                .reactiveLimits(ReactiveCapabilityCurveAttributes.builder()
                                        .points(curve)
                                        .ownerDescription("curve owner")
                                        .build())
                                .regulatingPoint(regulatingPoint("gen-curve", ResourceType.GENERATOR, "shunt-linear"))
                                .build())
                        .build(),
                Resource.generatorBuilder().id("gen-bare").variantNum(0)
                        .attributes(GeneratorAttributes.builder()
                                .voltageLevelId("vl2")
                                .targetP(0)
                                .build())
                        .build()));

        repository.createBatteries(NETWORK_UUID, List.of(
                Resource.batteryBuilder().id("bat-curve").variantNum(0)
                        .attributes(BatteryAttributes.builder()
                                .voltageLevelId("vl1")
                                .targetP(50)
                                .maxP(100)
                                .minP(0)
                                .reactiveLimits(ReactiveCapabilityCurveAttributes.builder()
                                        .points(new TreeMap<>(curve))
                                        .build())
                                .build())
                        .build()));

        repository.createShuntCompensators(NETWORK_UUID, List.of(
                Resource.shuntCompensatorBuilder().id("shunt-linear").variantNum(0)
                        .attributes(ShuntCompensatorAttributes.builder()
                                .voltageLevelId("vl1")
                                .model(ShuntCompensatorLinearModelAttributes.builder()
                                        .bPerSection(1).gPerSection(2).maximumSectionCount(3).build())
                                .sectionCount(2)
                                .regulatingPoint(regulatingPoint("shunt-linear", ResourceType.SHUNT_COMPENSATOR, "shunt-linear"))
                                .build())
                        .build(),
                Resource.shuntCompensatorBuilder().id("shunt-nonlinear").variantNum(0)
                        .attributes(ShuntCompensatorAttributes.builder()
                                .voltageLevelId("vl2")
                                .model(ShuntCompensatorNonLinearModelAttributes.builder()
                                        .sections(List.of(
                                                ShuntCompensatorNonLinearSectionAttributes.builder().b(1).g(2).build(),
                                                ShuntCompensatorNonLinearSectionAttributes.builder().b(3).g(4).build()))
                                        .build())
                                .build())
                        .build()));

        repository.createStaticVarCompensators(NETWORK_UUID, List.of(
                Resource.staticVarCompensatorBuilder().id("svc-1").variantNum(0)
                        .attributes(StaticVarCompensatorAttributes.builder()
                                .voltageLevelId("vl1")
                                .bmin(-1)
                                .bmax(1)
                                .regulatingPoint(regulatingPoint("svc-1", ResourceType.STATIC_VAR_COMPENSATOR, "gen-minmax"))
                                .build())
                        .build()));

        repository.createVscConverterStations(NETWORK_UUID, List.of(
                Resource.vscConverterStationBuilder().id("vsc-1").variantNum(0)
                        .attributes(VscConverterStationAttributes.builder()
                                .voltageLevelId("vl1")
                                .lossFactor(0.5f)
                                .reactiveLimits(MinMaxReactiveLimitsAttributes.builder().minQ(-20).maxQ(20).build())
                                .regulatingPoint(regulatingPoint("vsc-1", ResourceType.VSC_CONVERTER_STATION, "vsc-1"))
                                .build())
                        .build()));

        repository.createLccConverterStations(NETWORK_UUID, List.of(
                Resource.lccConverterStationBuilder().id("lcc-1").variantNum(0)
                        .attributes(LccConverterStationAttributes.builder()
                                .voltageLevelId("vl2")
                                .powerFactor(0.9f)
                                .build())
                        .build()));
    }

    private static RegulatingPointAttributes regulatingPoint(String id, ResourceType type, String regulatedId) {
        return RegulatingPointAttributes.builder()
                .regulatingEquipmentId(id)
                .regulatedResourceType(type)
                .localTerminal(TerminalRefAttributes.builder().connectableId(id).build())
                .regulatingTerminal(TerminalRefAttributes.builder().connectableId(regulatedId).build())
                .build();
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
    void generators() throws Exception {
        // sanity: the curve generator's satellite points really reach the response
        assertTrue(repository.getGenerators(NETWORK_UUID, 0).stream()
                .filter(g -> g.getId().equals("gen-curve"))
                .allMatch(g -> g.getAttributes().getReactiveLimits() instanceof ReactiveCapabilityCurveAttributes c
                        && c.getPoints() != null && c.getPoints().size() == 3));
        assertStreamedEquals("generators", () -> repository.getGenerators(NETWORK_UUID, 0));
    }

    @Test
    void batteries() throws Exception {
        assertStreamedEquals("batteries", () -> repository.getBatteries(NETWORK_UUID, 0));
    }

    @Test
    void shuntCompensators() throws Exception {
        assertStreamedEquals("shunt-compensators", () -> repository.getShuntCompensators(NETWORK_UUID, 0));
    }

    @Test
    void staticVarCompensators() throws Exception {
        assertStreamedEquals("static-var-compensators", () -> repository.getStaticVarCompensators(NETWORK_UUID, 0));
    }

    @Test
    void vscConverterStations() throws Exception {
        assertStreamedEquals("vsc-converter-stations", () -> repository.getVscConverterStations(NETWORK_UUID, 0));
    }

    @Test
    void lccConverterStations() throws Exception {
        assertStreamedEquals("lcc-converter-stations", () -> repository.getLccConverterStations(NETWORK_UUID, 0));
    }

    @Test
    void partialVariantFallsBackAndStaysEquivalent() throws Exception {
        repository.cloneNetworkVariant(NETWORK_UUID, 0, 1, "v1");
        assertStreamedEquals("generators", () -> repository.getGenerators(NETWORK_UUID, 0));
        String streamed = restTemplate.getForObject(
                "http://localhost:" + port + "/v1/networks/" + NETWORK_UUID + "/1/generators", String.class);
        List<Resource<GeneratorAttributes>> resources = repository.getGenerators(NETWORK_UUID, 1);
        TopLevelDocument<GeneratorAttributes> document = TopLevelDocument.of(resources);
        document.addMeta("totalCount", Integer.toString(resources.size()));
        assertEquals(objectMapper.writeValueAsString(document), streamed);
    }
}
