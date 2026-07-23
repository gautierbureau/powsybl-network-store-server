/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server;

import com.powsybl.network.store.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Benchmark of the complete HTTP read path: controller, repository, PostgreSQL,
 * attribute POJO materialization and Jackson response serialization. This is the
 * path the DB-to-Jackson deserialization plan optimizes (see
 * docs/db-jackson-read-path-plan.md); the repository-level PerfBenchmarkPostgresIT
 * cannot see the serialization half of it.
 *
 * <p>Responses are consumed as raw bytes on purpose: no benchmark CPU is spent
 * deserializing on the client side, so measurements isolate the server.
 *
 * <p>Disabled unless run with -Drestbench=true and a postgres reachable at
 * localhost:5432 (db iidm, user/pw postgres). Not part of CI.
 *
 * <p>Run: mvn -pl network-store-server test -Drestbench=true -Dtest=RestReadBenchmarkIT
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("restbench")
@EnabledIfSystemProperty(named = "restbench", matches = "true")
class RestReadBenchmarkIT {

    private static final UUID NETWORK_UUID = UUID.fromString("2b3c4d5e-0000-0000-0000-000000000002");
    private static final int VARIANT = Resource.INITIAL_VARIANT_NUM;

    // sizing: JSON-heavy on purpose — the plan targets the JSON columns
    private static final int NB_LOADS = 100000;      // properties map on each
    private static final int NB_GENERATORS = 20000;  // regulating point + reactive limits
    private static final int NB_TWT = 5000;          // ratio tap changer, 25 steps each
    private static final int NB_STEPS_PER_TWT = 25;
    private static final int NB_SWITCHES = 50000;    // near-scalar-only: baseline row cost

    private static final int WARMUP = 3;
    private static final int ITER = 15;

    @Autowired
    private NetworkStoreRepository repository;
    @Autowired
    private javax.sql.DataSource dataSource;
    @Autowired
    private TestRestTemplate restTemplate;
    @LocalServerPort
    private int port;

    @AfterEach
    void tearDown() {
        repository.deleteNetwork(NETWORK_UUID);
    }

    @Test
    void benchmark() {
        seed();

        benchGet("/networks/" + NETWORK_UUID + "/" + VARIANT + "/loads");
        benchGet("/networks/" + NETWORK_UUID + "/" + VARIANT + "/generators");
        benchGet("/networks/" + NETWORK_UUID + "/" + VARIANT + "/2-windings-transformers");
        benchGet("/networks/" + NETWORK_UUID + "/" + VARIANT + "/switches");
        benchGet("/networks/" + NETWORK_UUID + "/" + VARIANT + "/loads/load42");
        benchGet("/networks/" + NETWORK_UUID + "/" + VARIANT + "/identifiables/gen42");
    }

    private void benchGet(String path) {
        String url = "http://localhost:" + port + "/v1" + path;
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        long bytes = 0;
        for (int i = 0; i < WARMUP; i++) {
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, request, byte[].class);
            assertNotNull(response.getBody());
            bytes = response.getBody().length;
        }
        long[] samples = new long[ITER];
        for (int i = 0; i < ITER; i++) {
            long t0 = System.nanoTime();
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, request, byte[].class);
            samples[i] = System.nanoTime() - t0;
            assertNotNull(response.getBody());
        }
        java.util.Arrays.sort(samples);
        System.out.printf("BENCH GET %-42s bytes=%-9d median=%.2fms min=%.2fms max=%.2fms%n",
                path.substring(path.indexOf('/', 11)), bytes,
                samples[ITER / 2] / 1e6, samples[0] / 1e6, samples[ITER - 1] / 1e6);
    }

    private void seed() {
        repository.deleteNetwork(NETWORK_UUID);
        repository.createNetworks(List.of(Resource.networkBuilder()
                .id(NETWORK_UUID.toString())
                .variantNum(VARIANT)
                .attributes(NetworkAttributes.builder()
                        .uuid(NETWORK_UUID)
                        .variantId("v0")
                        .fullVariantNum(-1)
                        .caseDate(java.time.ZonedDateTime.parse("2026-07-23T00:00:00Z"))
                        .build())
                .build()));

        List<Resource<LoadAttributes>> loads = new ArrayList<>(NB_LOADS);
        for (int i = 0; i < NB_LOADS; i++) {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("source", "restbench");
            properties.put("region", "r" + i % 12);
            properties.put("priority", String.valueOf(i % 5));
            loads.add(Resource.loadBuilder()
                    .id("load" + i)
                    .variantNum(VARIANT)
                    .attributes(LoadAttributes.builder()
                            .voltageLevelId("vl" + i % 500)
                            .name("load" + i)
                            .p0(10.0 + i)
                            .q0(3.0)
                            .p(10.5 + i)
                            .q(3.2)
                            .properties(properties)
                            .build())
                    .build());
        }
        repository.createLoads(NETWORK_UUID, loads);

        List<Resource<GeneratorAttributes>> generators = new ArrayList<>(NB_GENERATORS);
        for (int i = 0; i < NB_GENERATORS; i++) {
            generators.add(Resource.generatorBuilder()
                    .id("gen" + i)
                    .variantNum(VARIANT)
                    .attributes(GeneratorAttributes.builder()
                            .voltageLevelId("vl" + i % 500)
                            .name("gen" + i)
                            .targetP(100.0)
                            .targetQ(5.0)
                            .reactiveLimits(MinMaxReactiveLimitsAttributes.builder().minQ(-50).maxQ(50).build())
                            .regulatingPoint(RegulatingPointAttributes.builder()
                                    .regulatingEquipmentId("gen" + i)
                                    .regulatedResourceType(ResourceType.GENERATOR)
                                    .localTerminal(TerminalRefAttributes.builder().connectableId("gen" + i).build())
                                    .regulatingTerminal(TerminalRefAttributes.builder().connectableId("gen" + i).build())
                                    .build())
                            .build())
                    .build());
        }
        repository.createGenerators(NETWORK_UUID, generators);

        List<Resource<TwoWindingsTransformerAttributes>> twts = new ArrayList<>(NB_TWT);
        for (int i = 0; i < NB_TWT; i++) {
            List<TapChangerStepAttributes> steps = new ArrayList<>(NB_STEPS_PER_TWT);
            for (int s = 0; s < NB_STEPS_PER_TWT; s++) {
                steps.add(TapChangerStepAttributes.builder()
                        .rho(0.9 + s * 0.01).r(1.0).x(2.0).g(0.0).b(0.0)
                        .side(0).index(s).type(TapChangerType.RATIO)
                        .build());
            }
            twts.add(Resource.twoWindingsTransformerBuilder()
                    .id("twt" + i)
                    .variantNum(VARIANT)
                    .attributes(TwoWindingsTransformerAttributes.builder()
                            .voltageLevelId1("vl" + i % 500)
                            .voltageLevelId2("vl" + (i + 1) % 500)
                            .name("twt" + i)
                            .r(0.5).x(5.0).g(0.0).b(0.0)
                            .ratedU1(400).ratedU2(225)
                            .ratioTapChangerAttributes(RatioTapChangerAttributes.builder()
                                    .lowTapPosition(0)
                                    .tapPosition(NB_STEPS_PER_TWT / 2)
                                    .steps(steps)
                                    .build())
                            .build())
                    .build());
        }
        repository.createTwoWindingsTransformers(NETWORK_UUID, twts);

        List<Resource<SwitchAttributes>> switches = new ArrayList<>(NB_SWITCHES);
        for (int i = 0; i < NB_SWITCHES; i++) {
            switches.add(Resource.switchBuilder()
                    .id("sw" + i)
                    .variantNum(VARIANT)
                    .attributes(SwitchAttributes.builder()
                            .voltageLevelId("vl" + i % 500)
                            .kind(com.powsybl.iidm.network.SwitchKind.BREAKER)
                            .open(i % 7 == 0)
                            .build())
                    .build());
        }
        repository.createSwitches(NETWORK_UUID, switches);

        // settle planner statistics so measurements reflect steady state
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ANALYZE");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
