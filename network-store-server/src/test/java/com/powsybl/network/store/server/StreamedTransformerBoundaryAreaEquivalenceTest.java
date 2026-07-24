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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-diff tests of the streamed two-windings-transformer, boundary-line and
 * area collections: tap changer steps and tap changer regulating points merged
 * into the composite tap changer properties, operational limits groups merged
 * into a non-column applier-affected field, and area boundaries as a raw
 * satellite field.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamedTransformerBoundaryAreaEquivalenceTest {

    private static final UUID NETWORK_UUID = UUID.fromString("708192a3-0000-0000-0000-000000000007");

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
                .id("streamed-twt-boundary-area-network")
                .variantNum(0)
                .attributes(NetworkAttributes.builder()
                        .uuid(NETWORK_UUID)
                        .variantId("v0")
                        .fullVariantNum(-1)
                        .caseDate(ZonedDateTime.parse("2026-01-01T00:00:00.000Z"))
                        .build())
                .build()));

        repository.createTwoWindingsTransformers(NETWORK_UUID, List.of(
                Resource.twoWindingsTransformerBuilder().id("twt-ratio").variantNum(0)
                        .attributes(TwoWindingsTransformerAttributes.builder()
                                .voltageLevelId1("vl1").voltageLevelId2("vl2")
                                .name("ratio twt")
                                .r(0.5).x(5).ratedU1(400).ratedU2(225)
                                .ratioTapChangerAttributes(RatioTapChangerAttributes.builder()
                                        .lowTapPosition(0)
                                        .tapPosition(1)
                                        .targetDeadband(0.5)
                                        .steps(List.of(
                                                step(0, 0.95), step(1, 1.0), step(2, 1.05)))
                                        .regulatingPoint(tapChangerRegulatingPoint("twt-ratio", RegulatingTapChangerType.RATIO_TAP_CHANGER))
                                        .build())
                                .build())
                        .build(),
                Resource.twoWindingsTransformerBuilder().id("twt-phase").variantNum(0)
                        .attributes(TwoWindingsTransformerAttributes.builder()
                                .voltageLevelId1("vl2").voltageLevelId2("vl3")
                                .r(0.3).x(3)
                                .phaseTapChangerAttributes(PhaseTapChangerAttributes.builder()
                                        .lowTapPosition(10)
                                        .tapPosition(11)
                                        .steps(List.of(
                                                phaseStep(0, 5.0), phaseStep(1, 10.0)))
                                        .regulatingPoint(tapChangerRegulatingPoint("twt-phase", RegulatingTapChangerType.PHASE_TAP_CHANGER))
                                        .build())
                                .build())
                        .build(),
                Resource.twoWindingsTransformerBuilder().id("twt-bare").variantNum(0)
                        .attributes(TwoWindingsTransformerAttributes.builder()
                                .voltageLevelId1("vl1").voltageLevelId2("vl3")
                                .r(1).x(10)
                                .build())
                        .build()));

        repository.createBoundaryLines(NETWORK_UUID, List.of(
                Resource.boundaryLineBuilder().id("bl-limits").variantNum(0)
                        .attributes(BoundaryLineAttributes.builder()
                                .voltageLevelId("vl1")
                                .name("boundary with limits")
                                .p0(10).q0(1)
                                .selectedOperationalLimitsGroupId("group1")
                                .operationalLimitsGroups(Map.of("group1", OperationalLimitsGroupAttributes.builder()
                                        .id("group1")
                                        .currentLimits(LimitsAttributes.builder()
                                                .permanentLimit(400)
                                                .temporaryLimits(new TreeMap<>(Map.of(
                                                        600, TemporaryLimitAttributes.builder()
                                                                .name("tl600").value(450).acceptableDuration(600).build())))
                                                .build())
                                        .build()))
                                .build())
                        .build(),
                Resource.boundaryLineBuilder().id("bl-bare").variantNum(0)
                        .attributes(BoundaryLineAttributes.builder()
                                .voltageLevelId("vl2")
                                .p0(5)
                                .build())
                        .build()));

        repository.createAreas(NETWORK_UUID, List.of(
                Resource.areaBuilder().id("area-1").variantNum(0)
                        .attributes(AreaAttributes.builder()
                                .name("control area")
                                .areaType("ControlArea")
                                .voltageLevelIds(Set.of("vl1", "vl2"))
                                .interchangeTarget(120.5)
                                .areaBoundaries(List.of(
                                        AreaBoundaryAttributes.builder()
                                                .areaId("area-1")
                                                .boundaryBoundaryLineId("bl-limits")
                                                .ac(true)
                                                .build(),
                                        AreaBoundaryAttributes.builder()
                                                .areaId("area-1")
                                                .terminal(TerminalRefAttributes.builder().connectableId("twt-ratio").side("ONE").build())
                                                .ac(false)
                                                .build()))
                                .build())
                        .build(),
                Resource.areaBuilder().id("area-bare").variantNum(0)
                        .attributes(AreaAttributes.builder()
                                .areaType("BiddingZone")
                                .build())
                        .build()));
    }

    private static TapChangerStepAttributes step(int index, double rho) {
        return TapChangerStepAttributes.builder()
                .rho(rho).r(1).x(2).g(0).b(0).side(0).index(index).type(TapChangerType.RATIO)
                .build();
    }

    private static TapChangerStepAttributes phaseStep(int index, double alpha) {
        return TapChangerStepAttributes.builder()
                .rho(1).alpha(alpha).r(1).x(2).g(0).b(0).side(0).index(index).type(TapChangerType.PHASE)
                .build();
    }

    private static RegulatingPointAttributes tapChangerRegulatingPoint(String transformerId, RegulatingTapChangerType tapChangerType) {
        return RegulatingPointAttributes.builder()
                .regulatingEquipmentId(transformerId)
                .regulatedResourceType(ResourceType.TWO_WINDINGS_TRANSFORMER)
                .regulatingTapChangerType(tapChangerType)
                .localTerminal(TerminalRefAttributes.builder().connectableId(transformerId).build())
                .regulatingTerminal(TerminalRefAttributes.builder().connectableId(transformerId).build())
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
    void twoWindingsTransformers() throws Exception {
        // sanity: steps and the tap changer regulating point really reach the response
        assertTrue(repository.getTwoWindingsTransformers(NETWORK_UUID, 0).stream()
                .filter(t -> t.getId().equals("twt-ratio"))
                .allMatch(t -> t.getAttributes().getRatioTapChangerAttributes().getSteps().size() == 3
                        && t.getAttributes().getRatioTapChangerAttributes().getRegulatingPoint() != null));
        assertStreamedEquals("2-windings-transformers", 0, () -> repository.getTwoWindingsTransformers(NETWORK_UUID, 0));
    }

    @Test
    void boundaryLines() throws Exception {
        assertTrue(repository.getBoundaryLines(NETWORK_UUID, 0).stream()
                .filter(b -> b.getId().equals("bl-limits"))
                .allMatch(b -> b.getAttributes().getOperationalLimitsGroups(1).containsKey("group1")));
        assertStreamedEquals("boundary-lines", 0, () -> repository.getBoundaryLines(NETWORK_UUID, 0));
    }

    @Test
    void areas() throws Exception {
        assertStreamedEquals("areas", 0, () -> repository.getAreas(NETWORK_UUID, 0));
    }

    @Test
    void partialVariantFallsBackAndStaysEquivalent() throws Exception {
        repository.cloneNetworkVariant(NETWORK_UUID, 0, 1, "v1");
        assertStreamedEquals("2-windings-transformers", 1, () -> repository.getTwoWindingsTransformers(NETWORK_UUID, 1));
    }
}
