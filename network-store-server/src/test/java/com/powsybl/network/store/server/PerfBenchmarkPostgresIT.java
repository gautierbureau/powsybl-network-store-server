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

    // network sizing
    private static final int NB_LOADS = 100000;
    private static final int NB_GENERATORS = 5000;
    private static final int NB_LINES = 5000;
    private static final int NB_SWITCHES = 10000;

    // benchmark parameters
    private static final int SMALL_UPDATE_BATCH = 50;
    private static final int WARMUP = 3;
    private static final int ITER = 15;

    @Autowired
    private NetworkStoreRepository repository;

    @AfterEach
    void tearDown() {
        repository.deleteNetwork(NETWORK_UUID);
    }

    @Test
    void benchmark() {
        seed();

        // A) update a small batch on a large table (partitionResourcesByExistenceInVariant path)
        List<Resource<LoadAttributes>> smallBatch = new ArrayList<>();
        for (int i = 0; i < SMALL_UPDATE_BATCH; i++) {
            smallBatch.add(buildLoad(i, 3.14 + i));
        }
        long updateNs = bench("updateLoads(50 of " + NB_LOADS + ")", () -> {
            repository.updateLoads(NETWORK_UUID, smallBatch);
            return 0L;
        });

        // B) list all identifiable ids (UNION ALL path)
        long idsNs = bench("getIdentifiablesIds", () -> {
            int size = repository.getIdentifiablesIds(NETWORK_UUID, VARIANT).size();
            return (long) size;
        });

        // C) full collection read (slim fullVariantNum path, several network row reads)
        long gensNs = bench("getGenerators", () -> {
            int size = repository.getGenerators(NETWORK_UUID, VARIANT).size();
            return (long) size;
        });

        System.out.println("=== PERF RESULTS (median ms over " + ITER + " iters) ===");
        System.out.printf("updateLoads(%d of %d): %.2f ms%n", SMALL_UPDATE_BATCH, NB_LOADS, updateNs / 1e6);
        System.out.printf("getIdentifiablesIds     : %.2f ms%n", idsNs / 1e6);
        System.out.printf("getGenerators           : %.2f ms%n", gensNs / 1e6);
    }

    private long bench(String name, LongSupplier op) {
        for (int i = 0; i < WARMUP; i++) {
            op.getAsLong();
        }
        long[] samples = new long[ITER];
        for (int i = 0; i < ITER; i++) {
            long t0 = System.nanoTime();
            op.getAsLong();
            samples[i] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(samples);
        long median = samples[ITER / 2];
        System.out.printf("  %-28s median=%.2fms min=%.2fms max=%.2fms%n",
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
            gens.add(Resource.generatorBuilder()
                    .id("gen" + i)
                    .variantNum(VARIANT)
                    .attributes(GeneratorAttributes.builder().voltageLevelId("vl1").build())
                    .build());
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
    }

    private static Resource<LoadAttributes> buildLoad(int i, double p) {
        return Resource.loadBuilder()
                .id("load" + i)
                .variantNum(VARIANT)
                .attributes(LoadAttributes.builder().voltageLevelId("vl1").p(p).build())
                .build();
    }
}
