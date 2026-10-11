# Benchmark History

## Dataset
- 1,000,000 log lines across 8 files
- ~55 MB total (~6.9 MB per file)
- Generated with seed=42 via `LogGenerator` (reproducible)
- Format: `[yyyy-MM-dd HH:mm:ss.SSS] LEVEL STATUS ENDPOINT LATENCYms`

## Version progression (single run each)

| Version | Change | Wall time | Throughput | Heap | Parse failures |
|---------|--------|-----------|------------|------|----------------|
| v1 | `CopyOnWriteArrayList` — never finished | DNF (>2 min) | — | ~800 MB | unknown |
| v2 | Bounded `ArrayBlockingQueue` + writer thread | 2,133 ms | 468,823 lines/s | 26 MB | 984 |
| v3 | Fixed-width timestamps, robust parser | 1,121 ms | 892,060 lines/s | 61 MB | 0 |
| v4 | Human-readable report timestamps | 1,294 ms | 772,797 lines/s | 24 MB | 0 |
| v5 | Rich stats: levels, endpoints, percentiles | 1,705 ms | 586,510 lines/s | 17 MB | 0 |

## Final benchmark (5 runs, warm-up discarded)

| Metric | Value |
|--------|-------|
| Median wall time | 794 ms |
| Min wall time | 764 ms |
| Max wall time | 999 ms |
| Median throughput | 1,259,445 lines/sec |
| Median heap | 47 MB |

**Command:** `java -cp out com.logpulse.MultiFileAnalyzer --bench 5`

## Correctness invariants (verified)

- Input lines written by generator: **1,000,000**
- Lines parsed by analyzer: **1,000,000**
- Parse failures: **0**
- Error entries found: **419,838**
- Lines in `error-report.txt`: **419,838**
- Level breakdown sums to total: **499,824 + 200,550 + 299,626 = 1,000,000** ✅

## Notes

- Benchmarks run on Windows 11, single machine, all other applications closed.
- `--bench N` discards a warm-up run and reports the median of N measured runs. This is deliberate: JIT compilation makes the first run unrepresentative.
- Latency percentiles are approximate (~10 ms bucket resolution) via a fixed-size histogram. This trades precision for bounded memory.
- Report timestamps are formatted in the system default timezone to match the generator's output round-trip.