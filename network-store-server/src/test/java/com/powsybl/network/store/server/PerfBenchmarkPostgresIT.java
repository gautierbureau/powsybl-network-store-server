/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.network.store.server;

import com.powsybl.network.store.model.*;
import com.powsybl.network.store.model.svattributes.InjectionSvAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

import static com.powsybl.network.store.server.utils.PartialVariantTestUtils.createFullVariantNetwork;

/**
 * Ad-hoc benchmark against a real PostgreSQL database. Disabled unless run with -Dpgbench=true
 * and a postgres reachable at localhost:5432 (db iidm, user/pw postgres). Not part of CI.
 *
 * <p>Run: mvn -pl network-store-server test -Dpgbench=true -Dtest=PerfBenchmarkPostgresIT
 */
@SpringBootTest
@ActiveProfiles("pgbench")
@EnabledIfSystemProperty(named = "pgbench", matches = "true")
class PerfBenchmarkPostgresIT {

    private static final UUID NETWORK_UUID = UUID.fromString("1a2b3c4d-0000-0000-0000-000000000001");
    private static final int VARIANT = Resource.INITIAL_VARIANT_NUM;
    private static final int PARTIAL_VARIANT = 1;

    // network sizing
    private static final int NB_LOADS = 100000;
    private static final int NB_GENERATORS = 10000;
    private static final int NB_LINES = 5000;
    private static final int NB_SWITCHES = 10000;

    // benchmark parameters
    private static final int SMALL_UPDATE_BATCH = 50;
    private static final int WARMUP = 3;
    private static final int ITER = 15;

    @Autowired
    private NetworkStoreRepository repository;
    @Autowired
    private javax.sql.DataSource dataSource;

    @AfterEach
    void tearDown() {
        repository.deleteNetwork(NETWORK_UUID);
    }

    @Test
    void benchmark() {
        seed();

        // small update batches
        List<Resource<LoadAttributes>> loadBatch = new ArrayList<>();
        List<Resource<InjectionSvAttributes>> loadSvBatch = new ArrayList<>();
        List<Resource<GeneratorAttributes>> genBatch = new ArrayList<>();
        List<Resource<InjectionSvAttributes>> genSvBatch = new ArrayList<>();
        for (int i = 0; i < SMALL_UPDATE_BATCH; i++) {
            loadBatch.add(buildLoad(i, 3.14 + i));
            loadSvBatch.add(Resource.create(ResourceType.LOAD, "load" + i, VARIANT,
                    InjectionSvAttributes.builder().p(5.5 + i).q(6.6 + i).build()));
            genBatch.add(buildGenerator(i));
            genSvBatch.add(Resource.create(ResourceType.GENERATOR, "gen" + i, VARIANT,
                    InjectionSvAttributes.builder().p(7.7 + i).q(8.8 + i).build()));
        }

        bench("updateLoads(50)", () -> {
            repository.updateLoads(NETWORK_UUID, loadBatch);
            return 0L;
        });
        bench("updateLoadsSv(50)", () -> {
            repository.updateLoadsSv(NETWORK_UUID, loadSvBatch);
            return 0L;
        });
        bench("updateGenerators(50)", () -> {
            repository.updateGenerators(NETWORK_UUID, genBatch);
            return 0L;
        });
        bench("updateGeneratorsSv(50)", () -> {
            repository.updateGeneratorsSv(NETWORK_UUID, genSvBatch);
            return 0L;
        });

        // full-variant collection reads
        bench("getLoads[full]", () -> repository.getLoads(NETWORK_UUID, VARIANT).size());
        bench("getGenerators[full]", () -> repository.getGenerators(NETWORK_UUID, VARIANT).size());

        // partial-variant collection reads (variant 1 clones variant 0 with 100 updated loads/generators)
        bench("getLoads[partial]", () -> repository.getLoads(NETWORK_UUID, PARTIAL_VARIANT).size());
        bench("getGenerators[partial]", () -> repository.getGenerators(NETWORK_UUID, PARTIAL_VARIANT).size());

        // single identifiable get (all-tables join + completion queries)
        bench("getIdentifiable(load)", () -> repository.getIdentifiable(NETWORK_UUID, VARIANT, "load42").isPresent() ? 1 : 0);
        bench("getIdentifiable(gen)", () -> repository.getIdentifiable(NETWORK_UUID, VARIANT, "gen42").isPresent() ? 1 : 0);
        bench("getGenerator(gen)", () -> repository.getGenerator(NETWORK_UUID, VARIANT, "gen42").isPresent() ? 1 : 0);

        // variant clone full -> partial (clone-per-contingency pattern)
        bench("cloneVariant(full->partial)", () -> {
            repository.cloneNetworkVariant(NETWORK_UUID, VARIANT, 10, "bench-clone");
            return 0L;
        }, () -> repository.deleteNetwork(NETWORK_UUID, 10));
    }

    private long bench(String name, LongSupplier op) {
        return bench(name, op, null);
    }

    private long bench(String name, LongSupplier op, Runnable cleanup) {
        for (int i = 0; i < WARMUP; i++) {
            op.getAsLong();
            if (cleanup != null) {
                cleanup.run();
            }
        }
        long[] samples = new long[ITER];
        for (int i = 0; i < ITER; i++) {
            long t0 = System.nanoTime();
            op.getAsLong();
            samples[i] = System.nanoTime() - t0;
            if (cleanup != null) {
                cleanup.run();
            }
        }
        java.util.Arrays.sort(samples);
        long median = samples[ITER / 2];
        System.out.printf("BENCH %-28s median=%.2fms min=%.2fms max=%.2fms%n",
                name, median / 1e6, samples[0] / 1e6, samples[ITER - 1] / 1e6);
        return median;
    }

    private void seed() {
        repository.deleteNetwork(NETWORK_UUID);
        createFullVariantNetwork(repository, NETWORK_UUID, "bench-network", VARIANT, "v0");

        List<Resource<LoadAttributes>> loads = new ArrayList<>();
        for (int i = 0; i < NB_LOADS; i++) {
            loads.add(buildLoad(i, 1.0));
        }
        repository.createLoads(NETWORK_UUID, loads);

        List<Resource<GeneratorAttributes>> gens = new ArrayList<>();
        for (int i = 0; i < NB_GENERATORS; i++) {
            gens.add(buildGenerator(i));
        }
        repository.createGenerators(NETWORK_UUID, gens);

        List<Resource<LineAttributes>> lines = new ArrayList<>();
        for (int i = 0; i < NB_LINES; i++) {
            lines.add(Resource.lineBuilder()
                    .id("line" + i)
                    .variantNum(VARIANT)
                    .attributes(LineAttributes.builder().voltageLevelId1("vl1").voltageLevelId2("vl2").build())
                    .build());
        }
        repository.createLines(NETWORK_UUID, lines);

        List<Resource<SwitchAttributes>> switches = new ArrayList<>();
        for (int i = 0; i < NB_SWITCHES; i++) {
            switches.add(Resource.switchBuilder()
                    .id("sw" + i)
                    .variantNum(VARIANT)
                    .attributes(SwitchAttributes.builder().voltageLevelId("vl1").kind(com.powsybl.iidm.network.SwitchKind.BREAKER).build())
                    .build());
        }
        repository.createSwitches(NETWORK_UUID, switches);

        // partial variant with 100 updated loads and generators
        repository.cloneNetworkVariant(NETWORK_UUID, VARIANT, PARTIAL_VARIANT, "v1");
        List<Resource<LoadAttributes>> updatedLoads = new ArrayList<>();
        List<Resource<GeneratorAttributes>> updatedGens = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Resource<LoadAttributes> l = buildLoad(i, 2.0);
            l.setVariantNum(PARTIAL_VARIANT);
            updatedLoads.add(l);
            Resource<GeneratorAttributes> g = buildGenerator(i);
            g.setVariantNum(PARTIAL_VARIANT);
            updatedGens.add(g);
        }
        repository.updateLoads(NETWORK_UUID, updatedLoads);
        repository.updateGenerators(NETWORK_UUID, updatedGens);

        // settle planner statistics so measurements reflect steady state, not the
        // transient post-bulk-import state where the planner has no statistics yet
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ANALYZE");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Resource<LoadAttributes> buildLoad(int i, double p) {
        return Resource.loadBuilder()
                .id("load" + i)
                .variantNum(VARIANT)
                .attributes(LoadAttributes.builder().voltageLevelId("vl1").p(p).build())
                .build();
    }

    private static Resource<GeneratorAttributes> buildGenerator(int i) {
        // realistic generator: min/max reactive limits and a regulating point
        return Resource.generatorBuilder()
                .id("gen" + i)
                .variantNum(VARIANT)
                .attributes(GeneratorAttributes.builder()
                        .voltageLevelId("vl1")
                        .name("gen" + i)
                        .targetP(100.0)
                        .reactiveLimits(MinMaxReactiveLimitsAttributes.builder().minQ(-50).maxQ(50).build())
                        .regulatingPoint(RegulatingPointAttributes.builder()
                                .regulatingEquipmentId("gen" + i)
                                .regulatedResourceType(ResourceType.GENERATOR)
                                .localTerminal(TerminalRefAttributes.builder().connectableId("gen" + i).build())
                                .regulatingTerminal(TerminalRefAttributes.builder().connectableId("gen" + i).build())
                                .build())
                        .build())
                .build();
    }
}
