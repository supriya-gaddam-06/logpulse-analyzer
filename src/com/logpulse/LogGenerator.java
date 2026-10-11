package com.logpulse;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Random;

public class LogGenerator {

    /** Fixed-width timestamp: yyyy-MM-dd HH:mm:ss.SSS — always 23 chars. */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final String[] LEVELS = {
        "INFO", "INFO", "INFO", "INFO", "INFO",
        "WARN", "WARN",
        "ERROR", "ERROR", "ERROR"
    };

    private static final String[] ENDPOINTS = {
        "/api/payment", "/api/users", "/api/checkout", "/api/login",
        "/api/search", "/api/cart", "/api/orders", "/api/profile",
        "/api/notifications", "/api/recommendations"
    };

    public static void main(String[] args) throws IOException {
        int totalLines = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        int numFiles   = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        String outDir  = args.length > 2 ? args[2] : "logs";

        // Ensure the output directory exists — defensive, so the generator
        // never fails because of a missing folder.
        Files.createDirectories(Path.of(outDir));

        // Fixed seed → reproducible output. Benchmarks compare apples-to-apples.
        Random rnd = new Random(42);

        long baseTime = LocalDateTime.of(2026, 7, 25, 10, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();

        int perFile = totalLines / numFiles;
        long start = System.currentTimeMillis();

        for (int f = 0; f < numFiles; f++) {
            String path = outDir + "/server-" + f + ".log";
            try (BufferedWriter w = new BufferedWriter(new FileWriter(path), 1 << 20)) {
                for (int i = 0; i < perFile; i++) {
                    long ts = baseTime + (long) f * perFile * 3L + i * 3L + rnd.nextInt(2);
                    String level = LEVELS[rnd.nextInt(LEVELS.length)];
                    int status   = pickStatusForLevel(level, rnd);
                    String ep    = ENDPOINTS[rnd.nextInt(ENDPOINTS.length)];
                    int latency  = 5 + rnd.nextInt(status >= 500 ? 800 : 200);

                    String tsStr = LocalDateTime
                            .ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())
                            .format(TS_FMT);

                    // NOTE: '\n' — not '%n'. '%n' expands to CRLF on Windows,
                    // producing inconsistent line endings across platforms.
                    String line = "[" + tsStr + "] " + level + " " + status + " "
                            + ep + " " + latency + "ms\n";
                    w.write(line);
                }
            }
            System.out.println("Wrote " + path + " (" + perFile + " lines)");
        }

        long ms = System.currentTimeMillis() - start;
        System.out.printf("Done: %d lines across %d files in %d ms%n",
                totalLines, numFiles, ms);
    }

    /** Keep status consistent with level — realistic data, not random noise. */
    private static int pickStatusForLevel(String level, Random rnd) {
        if ("ERROR".equals(level)) {
            int[] errors = {500, 502, 503, 404, 403, 401};
            return errors[rnd.nextInt(errors.length)];
        }
        if ("WARN".equals(level)) {
            int[] warns = {301, 302, 400, 401, 404};
            return warns[rnd.nextInt(warns.length)];
        }
        int[] ok = {200, 200, 200, 201, 204};
        return ok[rnd.nextInt(ok.length)];
    }
}