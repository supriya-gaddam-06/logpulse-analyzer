# LogPulse

A multi-threaded Java log analytics engine that parses 1M+ log lines per second with bounded memory, producing a filtered error report and aggregate statistics.

Built to explore a real performance problem: **how do you stream large amounts of data through a concurrent pipeline without the memory blowing up?**

---

## What it does

- Reads multiple log files in parallel (one thread per file)
- Parses each line with a hand-rolled, zero-regex parser
- Streams error entries to disk through a bounded queue and a dedicated writer thread
- Produces an error report plus aggregate stats: level breakdown, top error endpoints, and p50/p95/p99 latency

Sample input line:
```
[2026-07-25 10:00:00.001] ERROR 500 /api/payment 142ms
```

Sample output summary:
```
--- Summary ---
Total lines parsed : 1000000
Parse failures     : 0
Error entries      : 419838 (41.98% of all lines)
  INFO             : 499824
  WARN             : 200550
  ERROR            : 299626

Top 5 error endpoints:
  /api/cart                : 42265
  /api/recommendations     : 42143
  /api/search              : 42042
  /api/notifications       : 42037
  /api/users               : 42013

Latency percentiles (error entries, ~10ms bucket):
  p50 : ~140 ms
  p95 : ~690 ms
  p99 : ~780 ms
  min : ~0 ms
  max : ~800 ms
```

---

## Architecture

```
        ┌──────────────┐
        │  Log files   │  (8 files, ~6.9 MB each)
        └──────┬───────┘
               │
        ┌──────▼───────┐
        │  8 parser    │  ExecutorService, one thread per file
        │   threads    │  hand-rolled parser, no regex
        └──────┬───────┘
               │  formatted error lines
        ┌──────▼────────────────────┐
        │  ArrayBlockingQueue(10k)  │  bounded → backpressure
        └──────┬────────────────────┘
               │
        ┌──────▼───────┐
        │   Writer     │  dedicated thread, streams to disk
        │   thread     │  never holds entries in memory
        └──────┬───────┘
               │
        ┌──────▼───────┐
        │ error-report │
        └──────────────┘
```

**Key design decisions:**

- **Bounded queue + dedicated writer thread** — parsers never touch disk; the writer never parses. When the writer falls behind, producers block. This is backpressure, and it's what keeps memory flat regardless of input size.
- **Hand-rolled parser** — regex and `DateTimeFormatter` allocate and backtrack; a manual index-based parser does exactly the work the input requires. 3–10× faster on the hot path.
- **`LongAdder` counters** — `AtomicLong` serializes threads on CAS; `LongAdder` maintains per-thread cells and sums at the end. Near-zero contention.
- **Fixed-size histogram for percentiles** — 201 buckets of 10 ms. Trades precision for bounded memory, which is the right call for monitoring.

---

## Benchmark

Dataset: 1,000,000 log lines across 8 files (~55 MB total), generated with seed=42 for reproducibility.

| Version | Change | Wall time | Throughput | Peak heap |
|---------|--------|-----------|------------|-----------|
| v1 | `CopyOnWriteArrayList` aggregation | never finished (>2 min) | — | ~800 MB |
| v2 | Bounded `ArrayBlockingQueue` + writer thread | 2,133 ms | 468,823 lines/s | 26 MB |
| v3 | Fixed-width timestamps + robust parser | 1,121 ms | 892,060 lines/s | 61 MB |
| v5 | Rich stats (levels, endpoints, percentiles) | 1,705 ms | 586,510 lines/s | 17 MB |

**Final benchmark (median of 5 runs, warm-up discarded):**

| Metric | Value |
|--------|-------|
| Median wall time | 794 ms |
| Median throughput | 1,259,445 lines/sec |
| Median heap | 47 MB |

Full methodology and history in [`BENCHMARK.md`](BENCHMARK.md).

---

## How to run

**Prerequisites:** Java 17+ (tested on Java 21), PowerShell (Windows) or Bash.

**1. Generate the dataset (1M lines, ~55 MB across 8 files):**

```powershell
javac -d out src\com\logpulse\*.java
java -cp out com.logpulse.LogGenerator 1000000 8 logs
```

**2. Run the analyzer (single, verbose):**

```powershell
java -cp out com.logpulse.MultiFileAnalyzer
```

**3. Run the benchmark (5 runs, warm-up discarded):**

```powershell
java -cp out com.logpulse.MultiFileAnalyzer --bench 5
```

Or use the provided build script:
```powershell
.\run.ps1
```

Output: `logs/error-report.txt` (human-readable, ~19 MB for 1M input lines).

---

## Project structure

```
.
├── BENCHMARK.md                       # Benchmark history + methodology
├── README.md                          # You are here
├── run.ps1                            # Clean-compile-and-run script
├── src/com/logpulse/
│   ├── LogEntry.java                  # Immutable record (timestamp, level, status, endpoint, latency)
│   ├── LogGenerator.java              # Reproducible 1M-line dataset generator (seed=42)
│   ├── LogParser.java                 # Hand-rolled, zero-regex parser
│   └── MultiFileAnalyzer.java         # Multi-threaded pipeline + stats + benchmark mode
└── logs/                              # Output directory (gitignored except .gitkeep)
```

---

## Correctness

- 1,000,000 lines written by the generator
- 1,000,000 lines parsed by the analyzer
- **0 parse failures**
- 419,838 error entries found
- 419,838 lines in `error-report.txt` (exact match — no data loss)

---

## What I'd improve next

- **Add virtual threads (Java 21)** — replace the 8-thread pool with `Executors.newVirtualThreadPerTaskExecutor()` to scale to hundreds of files.
- **Validate against real logs** — the synthetic generator uses a fixed format; validating against LogHub's Apache dataset would catch real-world variations (multi-line stack traces, mixed encodings).
- **Ship as a CLI tool** — add picocli for argument parsing, and a `--format <name>` flag to swap parser implementations per log format.
- **Structured output** — emit JSON or CSV alongside the plain-text report for downstream ingestion.