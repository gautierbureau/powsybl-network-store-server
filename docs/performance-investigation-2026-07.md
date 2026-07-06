# Network-store server — performance investigation (July 2026)

Two rounds of profiling against a real PostgreSQL 16 instance found one ~10–30× win in the
update path, one ~120× post-import pathology, one missing index verified by `EXPLAIN`, and two
candidate optimizations that measurement rejected. All changes are on
[PR #3](https://github.com/gautierbureau/powsybl-network-store-server/pull/3)
(commits `77fb789`, round 1, and `6b6d18f`, round 2).

| | |
|---|---|
| Repository | `gautierbureau/powsybl-network-store-server` |
| Branch / PR | `claude/session-unu7cf` → PR #3 |
| Method | PostgreSQL 16.13, fresh database per run, statistics settled, medians of 15 iterations |

## Results at a glance

| Finding | Scenario | Before | After | Change |
|---|---|---:|---:|---|
| F1 — ANALYZE after bulk create | First flush right after importing 100k loads | 550 ms | 4–5 ms | **~120×** |
| F2 — IN-clause existence check | updateLoads(50) on a 100k-row table | 40.2 ms | 4.0 ms | **~10×** |
| F2 | updateLoads(50) on a 200k-row table | 95.6 ms | 3.2 ms | **~30×** |
| F3 — regulatingpoint index | getGenerator (single get) | 1.74 ms | 0.88 ms | **~2×** |
| F3 | getIdentifiable(generator) | 2.71 ms | 1.72 ms | 1.6× |
| F4 — delete of >1000 ids | deleteLoads with 1001 ids in one call | SQL error | works | bug fix |
| F5 — row-binding fast paths | Client-side binding, 100k-row read (matched probe) | 85–105 ms | 41–53 ms | ~2× (within end-to-end noise) |

## Context

The network-store server persists PowSyBl grid networks (loads, generators, lines, switches,
transformers and their satellite attributes) in PostgreSQL behind a REST API. Its hottest
production paths are the flush of computation results — many small update batches per variant —
and the preloading of whole collections into client caches.

Two pull requests already address request-level batching:
[#1](https://github.com/gautierbureau/powsybl-network-store-server/pull/1) (one round trip to
read all collections of a variant) and
[#2](https://github.com/gautierbureau/powsybl-network-store-server/pull/2) (one round trip to
apply a whole flush). This investigation deliberately went one layer down, at the SQL each
repository method issues, so its wins compound with those PRs rather than overlapping them.

## Methodology — and what it taught us

A local PostgreSQL 16 cluster was provisioned and an opt-in benchmark
(`PerfBenchmarkPostgresIT`, run with `-Dpgbench=true`, skipped in CI) seeds a representative
network — 100k loads, 10k generators with regulating points, 5k lines, 10k switches, plus a
partial variant — and times small-batch updates, state-variable updates, full and
partial-variant collection reads, single-identifiable gets, and variant clones. Production JDBC
options (`reWriteBatchedInserts=true`) are used. Each figure is the median of 15 iterations
after 3 warmups.

Beyond the timings, three methodological lessons were load-bearing:

- **Database state dominates naive comparisons.** The same code measured 2.8 ms or 632 ms for
  the same operation depending on whether planner statistics existed and how much dead-tuple
  bloat prior runs had left. Final comparisons therefore use a freshly created database per
  run, with statistics settled by an explicit `ANALYZE` after seeding. This "noise" turned out
  to be finding F1.
- **Verify the mechanism, not just the timing.** Each hypothesis was checked against the server
  itself: `EXPLAIN ANALYZE` on the exact query shapes, and per-statement duration logging
  (`log_min_duration_statement=0`) with marker statements to isolate one repository call. This
  is how one theory (commit-fsync overhead) was rejected and another (a sequential scan)
  confirmed.
- **Localhost hides latency-bound wins.** Round trips are nearly free on localhost, so anything
  whose only benefit is "fewer requests" measures flat here. Structural wins — less data
  scanned, better plans, less CPU — measure honestly.

## Findings

Numbered by impact. F1–F3 and F5 are performance changes; F4 is a correctness fix found along
the way.

### F1 — Bulk creation now refreshes planner statistics (~120×)

After a network import fills a table with, say, 100k rows, PostgreSQL has no statistics on it
until autoanalyze catches up. In that window the planner treats the table as small, and the
per-row `UPDATE … WHERE networkUuid=? AND variantNum=? AND id=?` statements of a first flush
are planned as **sequential scans** — 50 updates scan the 100k-row table 50 times. Measured on
a fresh database: **550 ms** for a 50-row flush, against **4–5 ms** once statistics exist. This
is precisely the production scenario of running a load flow immediately after importing a
network.

`createIdentifiables` now issues a best-effort `ANALYZE <table>` (PostgreSQL only, never
failing the creation) whenever it inserts 1000+ resources.

### F2 — Update existence checks are O(batch), not O(table) (~10–30×)

Every update endpoint first partitions incoming resources into "already exists → UPDATE" and
"new in this variant → INSERT". That check used to fetch *every id in the table for the
variant* into a set — updating 50 switches on a 100k-switch network read 100k ids first. It now
queries only the incoming ids with a batched `IN` clause. The win grows with table size
(40 → 4 ms at 100k rows, 96 → 3 ms at 200k) and, unlike everything else measured on localhost,
is independent of network latency.

### F3 — A missing index on `regulatingpoint`

Deleting or selecting regulating points by equipment id cannot use the primary key
`(networkuuid, variantnum, regulatingequipmenttype, regulatingtapchangertype,
regulatingequipmentid)`, because the unconstrained `regulatingtapchangertype` sits before the
id column. `EXPLAIN` confirmed a sequential scan of all the variant's regulating points on
**every** update of a generator, shunt, SVC, VSC or transformer — degrading further as each
update's delete-and-reinsert left dead tuples behind (measured 9 → 21 ms per cycle over 18
updates; stable with the index). A Liquibase changeset adds
`(networkuuid, variantnum, regulatingequipmenttype, regulatingequipmentid)`. Single-equipment
reads use the same query shape, hence the 2× on `getGenerator`.

### F4 — Deleting more than 1000 identifiables in one call failed

The delete prepared its `IN` clause once with `ids.size()` placeholders but bound them per
1000-id partition, leaving the rest unbound — a guaranteed `Parameter "#1003" is not set` error
for any delete of more than 1000 ids. Verified by regression test with the fix reverted; the
statement is now built per partition.

### F5 — Cheaper row-to-attributes binding (honest: modest)

About half the cost of a 100k-row collection read was client-side. Three reductions:
type-specialized `ResultSet` accessors instead of `getObject(int, Class)` dispatch, the Jackson
`MapType` for map columns cached per column instead of rebuilt per row, and plain array
iteration instead of a per-row `LinkedHashMap.forEach` with a boxed counter. A same-JVM matched
probe shows the binding overhead roughly halved; end-to-end medians on a quiet database move
within noise, so this is kept as a low-risk improvement that mainly shows under memory and GC
pressure, not as a headline.

## Measured and rejected

Documented so nobody re-chases them:

- **Merging the 24 per-table id queries into one `UNION ALL`.** Flat to slightly worse on
  localhost — its only benefit (24 round trips → 1) is latency-bound, and the wider result set
  plus client-side regrouping ate it. Implemented, measured, reverted.
- **Consolidating composite updates into one transaction.** An `updateGenerators` call opens
  ~7 connections and commits ~6 times. The theory that commit fsyncs dominated was tested by
  A/B with `synchronous_commit=off`: only ~15% moved, so it is not the bottleneck at this
  scale. Worth revisiting someday for *atomicity* (a failure mid-update currently leaves
  partial state), but not as a performance change.

## Steady-state benchmark, before and after

Fresh database per run, statistics settled, medians of 15 iterations. F1's effect is not
visible here by construction (statistics are settled in both runs); it is measured separately
above.

| Operation | Baseline | Optimized | Reading |
|---|---:|---:|---|
| updateLoads(50) | 2.82 ms | 2.74 ms | flat |
| updateLoadsSv(50) | 2.15 ms | 2.13 ms | flat |
| updateGenerators(50) | 9.79 ms | 8.91 ms | −9%, degradation eliminated |
| updateGeneratorsSv(50) | 2.25 ms | 2.14 ms | flat |
| getLoads, full variant (100k) | 112.8 ms | 114.3 ms | within noise |
| getLoads, partial variant | 132.1 ms | 115.2 ms | noisy baseline |
| getGenerators, full / partial | 52.1 / 54.1 ms | 50.2 / 48.5 ms | −4 / −10% |
| getIdentifiable(generator) | 2.71 ms | 1.72 ms | −37% (F3) |
| getGenerator | 1.74 ms | 0.88 ms | −49% (F3) |
| cloneVariant(full→partial) | 2.01 ms | 1.58 ms | −21% |

The steady-state deltas are deliberately modest: the baseline for this table already contains
the round-1 fixes, and F1/F2's large factors appear in the scenario-specific measurements where
the pathology actually occurs.

> **Operational note.** The F1 mechanism also applies to any bulk-write path outside
> `createIdentifiables` (for example, restoring a dump). If a deployment sees slow first
> flushes on freshly imported networks despite this fix, check `autovacuum_naptime` and
> `autovacuum_vacuum_insert_threshold`; the delete-and-reinsert pattern of external attributes
> also benefits from healthy autovacuum on `regulatingpoint`.

## Reproducing the measurements

```bash
# PostgreSQL reachable at localhost:5432, database iidm, user/password postgres
mvn -pl network-store-server test -Dpgbench=true -Dtest=PerfBenchmarkPostgresIT

# For comparable runs, recreate the database between runs:
psql -U postgres -c "DROP DATABASE iidm" -c "CREATE DATABASE iidm"
```

The benchmark settles statistics after seeding so its figures reflect steady state; comment out
the seed's `ANALYZE` to reproduce the F1 pathology.

## State of the work

- All changes are pushed on branch `claude/session-unu7cf`, commits `77fb789` (round 1) and
  `6b6d18f` (round 2), with [PR #3](https://github.com/gautierbureau/powsybl-network-store-server/pull/3)
  describing both rounds.
- Tests: 103 unit + 14 server integration + 110 client-server integration tests green; the
  Liquibase changeset applies on both PostgreSQL and H2; a regression test covers the F4 bug
  (verified to fail with the fix reverted).
- Candidate future work, in rough order of expected value: transaction consolidation for
  atomicity, per-request memoization of tombstone lookups on partial variants, and re-testing
  PR #1/#2 style request batching under real network latency where it should shine.
