# Plan — faster DB → Jackson deserialization on the read path

Goal: make the server's read path (PostgreSQL rows → attribute objects → JSON response)
as fast as possible. The network-store client is used by other servers, so the wire
format is treated as frozen: every change below must produce byte-equivalent (or at
least semantically identical, golden-diff-verified) HTTP responses.

## The pipeline today, and why it is structurally slow

A collection read (`GET /networks/{uuid}/{variant}/loads`, …) makes **two full Jackson
passes** over the same data:

1. **DB → POJO.** Scalar columns are bound through `ColumnMapping` lambdas, but every
   complex column — regulating points, reactive limits, temporary limits, properties
   maps, tap changer steps, extensions — is stored as a JSON string and parsed with
   `mapper.readValue(resultSet.getString(...), X.class)`, one databind call **per cell**
   (`Utils.bindAttributes`, `ExtensionHandler`, `LimitsHandler`,
   `NetworkStoreRepository` regulating-point/area/tap-changer readers). Some call sites
   allocate a fresh `TypeReference` inside the row loop.
2. **POJO → HTTP JSON.** The fully materialized `List<Resource<T>>` is wrapped in a
   `TopLevelDocument` and re-serialized by Spring MVC's Jackson converter. The nested
   attributes that were just parsed from DB JSON are written back out as JSON,
   unchanged.

For a pass-through read the server parses JSON it already had, builds objects it never
inspects, and re-serializes them to nearly identical JSON. Everything else is tuning
around that structural fact.

## What existing measurements already tell us

- Scalar-binding micro-optimization is **not** where the time is: the row-binding
  fast-path work (typed ResultSet accessors, cached MapType) measured only modest
  gains on a real PostgreSQL (see `performance-investigation-2026-07.md`, F5).
- Repository-level `getLoads` on 100k rows is ~94 ms; the same data served over HTTP
  is far more expensive, and a whole-network read of the 13k-bus reference network
  materializes ~75 MB of JSON in server heap (26 MB for the switches collection
  alone) — the response-production layer dominates both CPU and the RAM peaks
  (see the RAM profile section of the investigation doc).
- Neither Jackson mapper (repository or Spring MVC) registers an accelerator module;
  all databind goes through reflection.

## Levers, ranked

### A. Cheap and safe — Jackson hygiene (days, low risk)

- Cache an `ObjectReader` per JSON column type (e.g. held in `ColumnMapping`) instead
  of `mapper.readValue(String, Class)` per cell; hoist per-call `TypeReference`
  allocations out of row loops.
- Register **Blackbird** on both mappers (repository + Spring response) — generated
  accessors instead of reflection, typically 15–30 % on databind-heavy paths.
- Expected: 10–30 % on JSON-heavy reads. Measurable in an afternoon with the
  benchmarks below.

### B. Structural — stop round-tripping JSON through POJOs (the real win)

- **Audit** each JSON column: pure pass-through, or inspected server-side?
  Partial-variant resolution works at row granularity (which id exists in which
  variant), not field granularity, so most attribute JSON should be opaque.
- **B1 — raw pass-through:** for opaque columns, carry the DB string to the response
  writer and emit it with `writeRawValue` — zero parse, zero re-serialize, wire format
  unchanged. Needs either a raw-holder in the model or a server-side custom
  serializer keyed per column.
- **B2 — streaming endpoints:** for the heaviest collection GETs, build the response
  with the Jackson streaming API while iterating the `ResultSet` — scalars written
  directly, JSON columns raw-copied — never constructing attribute POJOs. Turns the
  read path into a DB→socket pipe, and makes per-request server memory constant
  instead of linear in network size (this is also the fix for the response-
  materialization RAM ceiling).
- Caveat: paths that *assemble* attributes from satellite tables (regulating points,
  operational limits groups, tap changer steps merged into parent attributes) need a
  streaming merge or stay on the POJO path initially.

### C. Adjacent, out of scope here

The client-side parse in the servers that consume network-store is the mirror image;
registering Blackbird there is a one-line change in the client repository.

## Phased plan with measurement gates

| Phase | Content | Gate |
|---|---|---|
| 0 | Profile: JFR/async-profiler on the repository benchmark and on the REST read benchmark; produce a "JDBC / scalar bind / JSON-cell parse / response serialization" breakdown | breakdown table published |
| A | ObjectReader caching, TypeReference hoisting, Blackbird on both mappers | benchmark delta, keep what measures |
| B1 | Raw pass-through for audited opaque JSON columns | golden-diff response equality + benchmark delta |
| B2 | Streaming DB→JSON for the heaviest collection endpoints, opt-in per endpoint | golden-diff + latency + server RSS (benchmark/ram tooling) |

Every phase measured on a fresh, `ANALYZE`d database (methodology of
`performance-investigation-2026-07.md`), on both the synthetic seed and the committed
PEGASE 13k reference network.

## Measurement rig

- **Repository level:** `PerfBenchmarkPostgresIT` (opt-in `-Dpgbench=true`) — already
  exists on the benchmark branch.
- **Full HTTP path:** `RestReadBenchmarkIT` (opt-in `-Drestbench=true`, added with
  this doc) — boots the real server on a random port, seeds a JSON-heavy network
  (regulating points, reactive limits, properties, tap changer steps), then measures
  `GET` collection endpoints end to end, reporting median latency and payload size.
  Consuming raw bytes over HTTP is deliberate: it exercises controller → repository →
  PostgreSQL → POJO → Jackson → socket while spending no benchmark CPU on client-side
  deserialization. The powsybl network-store client is *not* needed — and not wanted —
  for server profiling; it is only useful for end-to-end scenarios (see
  `benchmark/ram/`).

## Baseline (2026-07-23, fresh analyzed DB, medians of 15)

`RestReadBenchmarkIT` seed: 100k loads (properties map), 20k generators (regulating
point + reactive limits), 5k two-windings transformers (25-step ratio tap changer
each), 50k switches (near-scalar).

| Endpoint | Payload | HTTP median | µs/row | Repository-level reference |
|---|---:|---:|---:|---:|
| `GET /loads` (100k) | 28.4 MB | 786 ms | 7.9 | ~94 ms |
| `GET /generators` (20k) | 15.8 MB | 337 ms | 16.8 | — |
| `GET /2-windings-transformers` (5k) | 10.2 MB | 468 ms | 93.5 | — |
| `GET /switches` (50k) | 8.9 MB | 180 ms | 3.6 | — |
| `GET /loads/load42` | 291 B | 4.3 ms | — | — |
| `GET /identifiables/gen42` | 796 B | 9.6 ms | — | — |

Two conclusions fall out immediately:

- **~85 % of a collection read happens above the repository.** 100k loads cost
  ~94 ms at repository level but 786 ms over HTTP on localhost — the POJO→Jackson
  response production dominates roughly 7:1. Phase B aims at the right layer.
- **Per-row cost tracks JSON/satellite density**: 3.6 µs (switches, scalar-only) →
  7.9 µs (loads, one properties map) → 16.8 µs (generators, two JSON columns) →
  93.5 µs (transformers, tap changer steps merged from a satellite table and
  serialized back). The JSON columns, not the scalars, are the cost drivers —
  consistent with the F5 result that scalar binding tuning moves little.

## Phase 0 results — where the CPU actually goes (2026-07-23)

Method-sampling profile of the real server (exec jar built from this branch, `-Xmx2g`,
240 s JFR `settings=profile` recording, 13 116 execution samples) serving two
concurrent clients looping over the four collection GETs of the ACTIVSg70k network
(70k buses, 455k switches, 165k limit-group sides — `benchmark/networks/`).

Samples on Tomcat worker threads, bucketed by stack content:

| Category | Share of read-path CPU |
|---|---:|
| JSON parse, DB → POJO (Jackson databind + setup) | 30.0 % |
| JSON serialize, POJO → HTTP (Jackson + converter) | 25.3 % |
| Row binding incl. column `String` materialization | 23.1 % |
| Repository logic (query building, resource assembly) | 6.0 % |
| JDBC / PostgreSQL driver I/O | 4.1 % |
| Satellite merge (limits, steps, regulating points) | 1.2 % |
| Other (Tomcat/Spring, misc.) | 10.3 % |

So **~78 % of read CPU is the double-conversion machinery** (parse + serialize +
string materialization); the actual database fetch is ~4 %. The hot-methods view
sharpens where inside those buckets the time sits:

| Hot method | Share | Meaning |
|---|---:|---|
| `ObjectMapper._initForReading` | 14.3 % | per-`readValue` deserializer/type setup — **not parsing** |
| `String.<init>(byte[]…)` (+ `TextBuffer.contentsAsString`) | 16.7 % | materializing column text and parser buffers as `String`s |
| `Invokers$Holder.invokeExact_MT` + reflection accessors | 8.8 % | Jackson reflection + `ColumnMapping` lambda dispatch |
| `TypeFactory._fromClass` | 4.1 % | more per-call type resolution |
| `UTF8JsonGenerator.*` + bean/list serializers | ~11 % | actual response writing |
| `VisibleBufferedInputStream.ensureBytes` + `PgResultSet.getObject` | 5.5 % | genuine driver I/O |
| `java.util.logging.Logger.log` from `PgResultSet.getString` | 2.6 % | pgjdbc logs per `getString` call — pure call-volume overhead |
| `ConcurrentHashMap.get` / `LinkedDeque.contains` / `ThreadLocal` | ~5.6 % | Jackson deserializer-cache lookups, again per `readValue` |

Consequences for the phases:

- **Phase A is bigger than estimated.** `_initForReading` + `TypeFactory` + the
  cache-lookup churn ≈ **20 % of total read CPU** spent *setting up* `readValue`
  calls, all of which cached `ObjectReader`s eliminate. With Blackbird on top
  (the 8.8 % reflection bucket), phase A realistically targets 25–30 %, not 10–30 %
  of the JSON-heavy share.
- **Phase B attacks the rest**: raw pass-through removes the remaining parse time,
  most of the 16.7 % string materialization, and much of the ~11 % re-serialization
  for opaque columns.
- **Free win found**: pgjdbc's per-`getString` `java.util.logging` calls cost 2.6 %
  even with logging disabled at the JUL level; verify a hard-disabled driver logger
  (`loggerLevel=OFF` / JUL config) in production images.
- Satellite-table merging (1.2 %) is *not* a CPU problem at read time — B2's
  streaming-merge complexity should not be paid for CPU reasons; it is only needed
  where B2 streams those endpoints anyway.

## Phase A results — one keep, one honest rejection (2026-07-23)

Both phase-A candidates were implemented on independent branches off `main` and
measured with `RestReadBenchmarkIT` (fresh analyzed database per run, medians of 15).

**A1 — cached `ObjectReader` per column (`claude/cached-object-readers`): FLAT.**
All collection endpoints within run-to-run noise of the baseline. The phase-0
attribution above was wrong about *which part* of `_initForReading` is eliminable:
Jackson already caches root deserializers (the lookup is a `ConcurrentHashMap` hit),
so what a cached reader saves is negligible. The dominant per-cell cost inside
`_initForReading` is **`JsonParser` + `DeserializationContext` creation, which
`ObjectReader` pays too**. There is no cheap way around it — the only way to not pay
per-cell parser setup is to not parse (phase B). The branch is kept for its
allocation hygiene (`TypeReference`-per-row removal) but not proposed as a
performance change.

**A2 — Blackbird (`claude/blackbird-jackson`, PR #17): KEEP.**

| Endpoint | Baseline | Blackbird (2 runs) |
|---|---:|---:|
| 2-windings-transformers (5k, tap steps) | 375 ms | **300 / 304 ms (−19 to −20 %)** |
| generators (20k) | 276 ms | 239 / 263 ms (−5 to −13 %) |
| loads (100k) | 545 ms | 495 / 561 ms (noise) |
| switches (50k, scalar) | 137 ms | 133 / 118 ms |

The win lands where databind density is highest, matching the ~9 % reflection bucket
plus part of the serializer bucket. Loads/switches stay put because their cost is
string materialization and raw write throughput — Blackbird does not touch those.

**Revised conclusion.** Phase A's realistic total is the Blackbird win (~5–20 %
depending on endpoint), not the 25–30 % hoped for above: the per-`readValue` setup
cost is structural, not a caching miss. This *strengthens* the case for phase B —
raw pass-through / streaming is the only lever that removes parser setup, string
materialization, and re-serialization together, which the profile puts at ~55–60 %
of read CPU combined.

## Open questions

1. Is the wire format strictly frozen? (Assumed yes — golden-diff equality is the
   acceptance test for B1/B2.)
2. Can the model classes (client repository) be touched if B1 needs a raw-holder
   type, or must the first iteration stay server-only?
3. Which endpoints dominate production traffic — whole-collection reads by loadflow
   workers, or single-identifiable gets — to aim B2 at the right tables first?
