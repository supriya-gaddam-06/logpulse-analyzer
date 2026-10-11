package com.logpulse;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;

public class MultiFileAnalyzer {

    // ---------- Configuration ----------
    private static final int NUM_FILES        = 8;
    private static final int WRITER_QUEUE_CAP = 10_000;

    private static final int BUCKET_SIZE_MS = 10;
    private static final int BUCKET_COUNT   = 201;

    private static final Path REPORT = Path.of("logs", "error-report.txt");

    private static final DateTimeFormatter TS_OUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final String POISON = "__POISON__";

    public static void main(String[] args) throws Exception {
        int benchRuns = 0;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--bench".equals(args[i])) {
                benchRuns = Integer.parseInt(args[i + 1]);
            }
        }

        if (benchRuns > 0) {
            runBenchmark(benchRuns);
        } else {
            // Single run, verbose output
            RunResult r = analyze(true);
            if (r != null) {
                // analyze() already printed the report
            }
        }
    }

    // ---------- Benchmark mode ----------
    private static void runBenchmark(int n) throws Exception {
        System.out.println("=== Benchmark mode: " + n + " measured runs "
                + "(+1 warm-up, discarded) ===");
        System.out.println();

        // Warm-up run — result discarded.
        System.out.println("[warm-up] starting...");
        analyze(false);

        long[] times = new long[n];
        long[] heaps = new long[n];

        for (int i = 0; i < n; i++) {
            System.out.println("[run " + (i + 1) + "/" + n + "] starting...");
            RunResult r = analyze(false);
            if (r == null) {
                System.err.println("Run " + (i + 1) + " failed");
                return;
            }
            times[i] = r.wallMs;
            heaps[i] = r.heapAfterMB;
            System.out.printf("[run %d/%d] wall=%d ms  heap=%d MB  throughput=%,d lines/sec%n",
                    i + 1, n, r.wallMs, r.heapAfterMB,
                    r.totalLines * 1000L / Math.max(1, r.wallMs));
        }

        long[] sortedTimes = times.clone();
        Arrays.sort(sortedTimes);
        long medianMs = sortedTimes[n / 2];
        long minMs    = sortedTimes[0];
        long maxMs    = sortedTimes[n - 1];

        long[] sortedHeaps = heaps.clone();
        Arrays.sort(sortedHeaps);
        long medianHeap = sortedHeaps[n / 2];

        System.out.println();
        System.out.println("--- Benchmark Summary (" + n + " runs) ---");
        System.out.printf("Median wall time : %d ms%n", medianMs);
        System.out.printf("Min    wall time : %d ms%n", minMs);
        System.out.printf("Max    wall time : %d ms%n", maxMs);
        System.out.printf("Median heap      : %d MB%n", medianHeap);
        System.out.printf("Median throughput: %,d lines/sec%n",
                (times.length > 0 ? 1_000_000L * 1000L / medianMs : 0));
    }

    // ---------- Single analysis ----------
    private record RunResult(long wallMs, long heapAfterMB, long totalLines) {}

    /**
     * Runs the full analysis once.
     * @param verbose when true, prints the full summary (for interactive use).
     *                when false, only returns the timing (for benchmark mode).
     */
    private static RunResult analyze(boolean verbose) throws Exception {
        String[] files = new String[NUM_FILES];
        for (int i = 0; i < NUM_FILES; i++) files[i] = "logs/server-" + i + ".log";

        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();
        long start = System.currentTimeMillis();

        LongAdder totalLines    = new LongAdder();
        LongAdder parseErrors   = new LongAdder();
        LongAdder errorEntries  = new LongAdder();
        LongAdder infoCount     = new LongAdder();
        LongAdder warnCount     = new LongAdder();
        LongAdder errorLevelCnt = new LongAdder();
        LongAdder otherLevelCnt = new LongAdder();

        ConcurrentHashMap<String, LongAdder> endpointErrors = new ConcurrentHashMap<>();

        LongAdder[] latencyBuckets = new LongAdder[BUCKET_COUNT];
        for (int i = 0; i < BUCKET_COUNT; i++) latencyBuckets[i] = new LongAdder();

        BlockingQueue<String> errorQueue = new ArrayBlockingQueue<>(WRITER_QUEUE_CAP);
        Files.createDirectories(REPORT.getParent());

        Thread writerThread = new Thread(() -> {
            try (BufferedWriter w = Files.newBufferedWriter(REPORT)) {
                while (true) {
                    String line = errorQueue.take();
                    if (line == POISON) break;
                    w.write(line);
                }
            } catch (IOException | InterruptedException ex) {
                System.err.println("Writer failed: " + ex.getMessage());
            }
        }, "writer");
        writerThread.start();

        ExecutorService pool = Executors.newFixedThreadPool(NUM_FILES);
        List<Future<?>> futures = new ArrayList<>();
        for (String file : files) {
            futures.add(pool.submit(() -> {
                try (var reader = Files.newBufferedReader(Path.of(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        totalLines.increment();
                        LogEntry e = LogParser.parse(line);
                        if (e == null) { parseErrors.increment(); continue; }

                        switch (e.level()) {
                            case "INFO"  -> infoCount.increment();
                            case "WARN"  -> warnCount.increment();
                            case "ERROR" -> errorLevelCnt.increment();
                            default      -> otherLevelCnt.increment();
                        }

                        if (e.isError()) {
                            errorEntries.increment();
                            errorQueue.put(format(e));
                            endpointErrors
                                    .computeIfAbsent(e.endpoint(), k -> new LongAdder())
                                    .increment();
                            int bucket = Math.min(e.latencyMs() / BUCKET_SIZE_MS,
                                                  BUCKET_COUNT - 1);
                            latencyBuckets[bucket].increment();
                        }
                    }
                } catch (IOException | InterruptedException ex) {
                    System.err.println("Reader failed on " + file + ": " + ex.getMessage());
                }
            }));
        }

        for (Future<?> f : futures) f.get();
        pool.shutdown();

        errorQueue.put(POISON);
        writerThread.join();

        long end = System.currentTimeMillis();
        long heapAfter = rt.totalMemory() - rt.freeMemory();
        long total = totalLines.sum();
        long errors = errorEntries.sum();
        long wallMs = end - start;
        long heapAfterMB = heapAfter / (1024 * 1024);

        if (verbose) {
            double errRate = total == 0 ? 0 : (errors * 100.0 / total);

            System.out.println();
            System.out.println("--- Summary ---");
            System.out.println("Total lines parsed : " + total);
            System.out.println("Parse failures     : " + parseErrors.sum());
            System.out.printf ("Error entries      : %d (%.2f%% of all lines)%n", errors, errRate);
            System.out.println("  INFO             : " + infoCount.sum());
            System.out.println("  WARN             : " + warnCount.sum());
            System.out.println("  ERROR            : " + errorLevelCnt.sum());
            if (otherLevelCnt.sum() > 0) {
                System.out.println("  OTHER            : " + otherLevelCnt.sum());
            }

            System.out.println();
            System.out.println("Top 5 error endpoints:");
            endpointErrors.entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<String, LongAdder> en)
                            -> en.getValue().sum()).reversed())
                    .limit(5)
                    .forEach(en -> System.out.printf("  %-24s : %d%n",
                            en.getKey(), en.getValue().sum()));

            long p50 = percentile(latencyBuckets, errors, 50);
            long p95 = percentile(latencyBuckets, errors, 95);
            long p99 = percentile(latencyBuckets, errors, 99);
            long minLatency = firstNonEmptyBucketMs(latencyBuckets);
            long maxLatency = lastNonEmptyBucketMs(latencyBuckets);

            System.out.println();
            System.out.println("Latency percentiles (error entries, ~10ms bucket):");
            System.out.println("  p50 : ~" + p50 + " ms");
            System.out.println("  p95 : ~" + p95 + " ms");
            System.out.println("  p99 : ~" + p99 + " ms");
            System.out.println("  min : ~" + minLatency + " ms");
            System.out.println("  max : ~" + maxLatency + " ms");

            System.out.println();
            System.out.println("Total wall time    : " + wallMs + " ms");
            System.out.println("Throughput         : " +
                    (total * 1000L / Math.max(1, wallMs)) + " lines/sec");
            System.out.printf ("Heap before/after  : %d MB / %d MB%n",
                    heapBefore / (1024 * 1024), heapAfterMB);
            System.out.println("Report written to  : " + REPORT.toAbsolutePath());
        }

        return new RunResult(wallMs, heapAfterMB, total);
    }

    // ---------- Helpers ----------

    private static String format(LogEntry e) {
        String ts = LocalDateTime
                .ofInstant(Instant.ofEpochMilli(e.timestamp()), ZoneId.systemDefault())
                .format(TS_OUT);
        return "[" + ts + "] " + e.level() + " " + e.status() + " "
                + e.endpoint() + " " + e.latencyMs() + "ms\n";
    }

    private static long percentile(LongAdder[] buckets, long total, int pct) {
        if (total == 0) return 0;
        long target = (long) Math.ceil(total * pct / 100.0);
        long cumulative = 0;
        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i].sum();
            if (cumulative >= target) return (long) i * BUCKET_SIZE_MS;
        }
        return (long) (buckets.length - 1) * BUCKET_SIZE_MS;
    }

    private static long firstNonEmptyBucketMs(LongAdder[] buckets) {
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i].sum() > 0) return (long) i * BUCKET_SIZE_MS;
        }
        return 0;
    }

    private static long lastNonEmptyBucketMs(LongAdder[] buckets) {
        for (int i = buckets.length - 1; i >= 0; i--) {
            if (buckets[i].sum() > 0) return (long) i * BUCKET_SIZE_MS;
        }
        return 0;
    }
}