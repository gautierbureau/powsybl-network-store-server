# RAM profiling of a running network-store server

The `PerfBenchmarkPostgresIT` harness (one directory up, in the server test sources)
answers "how fast is this repository operation". The tools in this directory answer a
different question: **how much memory does the deployed server need under real HTTP
load** — full network imports and concurrent whole-network reads. They run the actual
production stack: the exec jar as its own JVM, the real REST client, and a 1-second
RSS sampler on the server process.

## Test network

`../networks/pegase13k-nodebreaker-limits.xiidm.gz` is the network all reference
measurements below were made with — powsybl importers read the `.xiidm.gz` directly,
no unpacking needed. It is the MATPOWER PEGASE 13 659-bus case converted to
node-breaker topology and completed with realistic data using
[test2](https://github.com/gautierbureau/test2): 13 659 busbar sections, 118k
switches, 39 798 operational limits groups (current limits on both sides of every
branch) and 4 092 `activePowerControl` generator extensions. 72 MB uncompressed,
~280k rows / 106 MB once imported into PostgreSQL.

Note when rebuilding it: the test2 steps must run in the order *node-breaker
conversion first, then* `add_current_limits.py` *and* `complete_network.py` —
`bus_to_node_breaker.py` rebuilds the network without copying operational limits
groups or generator extensions, so running it last silently drops them.

`../networks/activsg70k-nodebreaker-limits.xiidm.gz` is the same pipeline applied to
the MATPOWER ACTIVSg70k case (source in `../networks/sources/`): 70 000 buses,
73 309 busbar sections, 455k switches, limits on 165 382 branch sides *including
496k temporary limit tiers*, and 10 390 activePowerControl extensions — ~5× the
PEGASE network (250 MB uncompressed), for testing at CGMES-continental scale.
Pipeline cost: ~11 min and 3.4 GB peak python RSS (node-breaker 2m43s, limits
4m51s, completion 3m44s).

## Setup

Build the server and a client classpath (the network-store client comes in through the
tools module):

```bash
mvn -DskipTests package
mvn -pl network-store-tools dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
# powsybl-config-test on the classpath breaks standalone runs with
# "Multiple platform configuration providers found" — drop it:
tr ':' '\n' < /tmp/cp.txt | grep -v powsybl-config-test | paste -sd: > /tmp/cp-clean.txt

javac -cp "$(cat /tmp/cp-clean.txt)" -d classes ImportRunner.java ReadRunner.java
```

Start the server with GC logging and arm the sampler:

```bash
java -Xms512m -Xmx2g -Xlog:gc*:file=server-gc.log:time,uptime \
     -jar ../../network-store-server/target/powsybl-network-store-server-*-exec.jar \
     --server.port=8080 --powsybl-ws.database.host=localhost &
./sample-rss.sh $! > server-rss.log &
```

## Write path

```bash
java -Xmx4g -Xlog:gc*:file=client-gc.log:time,uptime \
     -cp "classes:$(cat /tmp/cp-clean.txt)" ImportRunner http://localhost:8080/ network.xiidm
```

## Read path

`ReadRunner` loads a network with a chosen preloading strategy and walks it (counts,
bus view, operational limits, extensions) to force full materialization, printing heap
usage at each step. Run several in parallel to simulate concurrent loadflow workers:

```bash
for i in 1 2 3 4; do
  java -Xmx2g -cp "classes:$(cat /tmp/cp-clean.txt)" \
       ReadRunner http://localhost:8080/ <network-uuid> COLLECTION > reader-$i.log &
done
wait
sort -t= -k2 -n server-rss.log | tail -1   # server RSS peak
grep -c 'Pause Full' server-gc.log          # should stay 0
```

## Reading the results

Three numbers matter, and they are easy to conflate:

- **Server RSS peak** (`server-rss.log`): what the OS/container actually needs. With G1
  this ratchets up to the high-water mark and does not come back down — a big RSS is
  not by itself evidence of a leak or of need.
- **Heap-before-GC** (`server-gc.log` young pause lines): mostly short-lived
  serialization garbage. G1 lazily fills whatever heap it is given before collecting,
  so this scales with `-Xmx`, not with the workload's live set.
- **Live set**: what the workload really requires. Find it by re-running with a much
  smaller `-Xmx` and checking that throughput holds and `Pause Full` stays at zero.

Measured reference points (13 659-bus PEGASE node-breaker network: 118k switches,
39.8k operational limit groups, ~280k rows, 106 MB in PostgreSQL — see
`docs/performance-investigation-2026-07.md` for the full write-up): a 768 MB heap
serves 4 concurrent whole-network readers as fast as a 2 GB heap does, with less total
GC pause time. The apparent ~150 MB-per-reader RSS growth seen with a roomy heap is
G1 slack, not live data. The genuine scaling limit is per-request response
materialization: each collection GET builds the full resource list and its JSON in
server heap (the switches response alone is 26 MB for this network), which grows
linearly with network size.
