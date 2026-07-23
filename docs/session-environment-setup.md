# Setting up the performance-test environment (session bootstrap)

Recipe to rebuild, in a fresh ephemeral session, the full environment used for the
July 2026 performance work: PostgreSQL, the server under test, the benchmarks, and
the PEGASE 13k reference network with its enhancement pipeline. Everything below
assumes a Debian-ish container with JDK 17+, Maven and Python 3 available, and this
repository checked out.

## 1. PostgreSQL (real measurements need it — H2 lies about plans)

```bash
apt-get install -y postgresql-16    # or the distro's default major version
# a session-local cluster; /tmp is fine, it only needs to outlive the session
sudo -u postgres /usr/lib/postgresql/16/bin/initdb -D /tmp/pgdata_bench
sudo -u postgres /usr/lib/postgresql/16/bin/pg_ctl -D /tmp/pgdata_bench \
     -o "-p 5432 -c listen_addresses=localhost" -l /tmp/pgdata_bench/logfile start
sudo -u postgres psql -p 5432 -c "ALTER USER postgres PASSWORD 'postgres';" \
     -c "CREATE DATABASE iidm;"
```

Notes learned the hard way:

- The container reclaims idle processes between working periods: **postgres may be
  dead at the start of any turn**. The data directory survives; just re-run the
  `pg_ctl ... start` line.
- Benchmark methodology requires a **fresh database per measured run**
  (`DROP DATABASE iidm; CREATE DATABASE iidm;`) and settled statistics (the harness
  seeds run `ANALYZE`). Comparing runs against a reused database measures bloat and
  planner-statistics state, not code.
- To see per-statement behaviour: `ALTER SYSTEM SET log_min_duration_statement = 0;`
  then `SELECT pg_reload_conf();`, and grep `/tmp/pgdata_bench/logfile`.

## 2. Server build and benchmarks

```bash
mvn -DskipTests package             # produces network-store-server/target/*-exec.jar

# repository-level latency benchmark (benchmark branch):
mvn -pl network-store-server test -Dpgbench=true -Dtest=PerfBenchmarkPostgresIT
# full HTTP read-path benchmark (this branch):
mvn -pl network-store-server test -Drestbench=true -Dtest=RestReadBenchmarkIT
```

To run the server standalone (RAM profiling, imports):

```bash
java -Xms512m -Xmx2g -Xlog:gc*:file=/tmp/server-gc.log:time,uptime \
     -jar network-store-server/target/powsybl-network-store-server-*-exec.jar \
     --server.port=8080 --powsybl-ws.database.host=localhost &
```

RAM tooling (RSS sampler, import/read runners, client-classpath recipe including the
`powsybl-config-test` exclusion) lives in `benchmark/ram/` on the benchmark branch
(`claude/perf-benchmark-and-notes`).

## 3. Python environment for network building

System pip fights debian-managed packages — use a venv:

```bash
python3 -m venv /tmp/venv && . /tmp/venv/bin/activate
pip install pypowsybl pandapower scipy numpy
```

## 4. The PEGASE 13k reference network

Already built and committed: `benchmark/networks/pegase13k-nodebreaker-limits.xiidm.gz`
on the benchmark branch — powsybl reads `.xiidm.gz` directly. Rebuild it (or build
variants) as follows.

### 4a. Get the raw case as XIIDM

pandapower's bundled PEGASE cases stop below 13k, so fetch the MATPOWER case and
convert; pypowsybl's MATPOWER importer wants a binary `.mat`, not the `.m` source:

```bash
curl -sL -o /tmp/case13659pegase.m \
  https://raw.githubusercontent.com/MATPOWER/matpower/master/data/case13659pegase.m
python m_to_mat.py /tmp/case13659pegase.m /tmp/case13659pegase.mat   # script below
python -c "import pypowsybl.network as pn; \
           pn.load('/tmp/case13659pegase.mat').save('/tmp/pegase13k.xiidm', format='XIIDM')"
```

`m_to_mat.py` is ~40 lines: regex-extract `mpc.baseMVA`, `mpc.bus/gen/branch/gencost`
matrices from the `.m` text into numpy arrays and `scipy.io.savemat(dst, {"mpc": mpc})`.

### 4b. Enhance with test2 (github.com/gautierbureau/test2)

```bash
git clone https://github.com/gautierbureau/test2 /workspace/test2
cd /workspace/test2/java && mvn -DskipTests package    # only needed for the java tools
```

**Order matters.** `python/bus_to_node_breaker.py` rebuilds the network and silently
**drops operational limits groups and generator extensions** (tap changers survive).
Convert topology first, then add data:

```bash
cd /workspace/test2/python
# 1. bus-breaker -> node-breaker: creates busbar sections + breakers/disconnectors
python bus_to_node_breaker.py /tmp/pegase13k.xiidm /tmp/pegase13k_nb.xiidm
# 2. realistic current limits on every branch side (permanent + temporary)
python add_current_limits.py /tmp/pegase13k_nb.xiidm /tmp/pegase13k_nb_limits.xiidm
# 3. missing data: activePowerControl extensions, energy sources, ...
python complete_network.py --active-power-control --energy-source \
       /tmp/pegase13k_nb_limits.xiidm /tmp/pegase13k_final.xiidm
```

Two more test2 gotchas:

- `bus_to_node_breaker.py --validate` false-fails with `dAngle = 360°`: the validator
  does not normalize angles modulo 360. Run without `--validate`.
- pypowsybl writes a network-level `referenceTerminals` extension on save; the
  network-store client has no adder for it and the import dies. Strip the 5-line
  `<iidm:extension id="..."><reft:referenceTerminals>...` block before importing.

Verify the result before using it (the numbers for the committed network):

```bash
zcat network.xiidm.gz | grep -c operationalLimitsGroup1   # 39 798 (19 899 per side)
zcat network.xiidm.gz | grep -c activePowerControl        #  4 092
zcat network.xiidm.gz | grep -c busbarSection             # 13 659+
```

### 4c. Ideas for bigger/richer variants

- Scale: import the network N times under different UUIDs (RAM/concurrency tests
  don't need topological connection), or merge N copies with pypowsybl for a true
  CGMES-continental-scale (~100k bus) single network.
- Richness: add temporary limit tiers in `add_current_limits.py`, more extension
  types in `complete_network.py`, or clone variants server-side and flush a full SV
  update to exercise partial-variant reads at scale.

## 5. Importing into the server

```bash
# client classpath: see benchmark/ram/README.md (build-classpath + drop powsybl-config-test)
java -Xmx4g -cp "classes:$(cat cp-clean.txt)" ImportRunner http://localhost:8080/ \
     benchmark/networks/pegase13k-nodebreaker-limits.xiidm.gz
```

~23 s for the reference network; verify with
`psql -d iidm -c "SELECT relname, n_live_tup FROM pg_stat_user_tables ORDER BY 2 DESC LIMIT 10"`
that `operationallimitsgroup` (~39.8k) and `extension` (~7k) are populated — an import
that silently lost them means the pipeline order above was not respected.
