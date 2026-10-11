package com.logpulse;

import java.time.LocalDateTime;
import java.time.ZoneId;

public final class LogParser {

    private LogParser() {}

    /**
     * Expected format:
     * [2026-07-25 10:00:00.001] ERROR 500 /api/payment 142ms
     *
     * Defensive by design:
     *  - Tolerates optional milliseconds (.SSS)
     *  - Tolerates a trailing '\r' (CRLF files)
     *  - Returns null on malformed input instead of throwing
     */
    public static LogEntry parse(String line) {
        if (line == null || line.isEmpty()) return null;

        // Strip a trailing '\r' if the file uses CRLF line endings.
        int len = line.length();
        if (len > 0 && line.charAt(len - 1) == '\r') {
            line = line.substring(0, len - 1);
            len--;
        }
        if (len == 0) return null;

        try {
            // --- timestamp between [ and ] ---
            if (line.charAt(0) != '[') return null;
            int close = line.indexOf(']');
            if (close < 0) return null;

            String tsStr = line.substring(1, close);
            long ts = parseTimestamp(tsStr);
            if (ts < 0) return null;

            // --- LEVEL ---
            int i = close + 1;
            while (i < len && line.charAt(i) == ' ') i++;
            int levelStart = i;
            while (i < len && line.charAt(i) != ' ') i++;
            if (i == levelStart) return null;
            String level = line.substring(levelStart, i);

            // --- STATUS ---
            while (i < len && line.charAt(i) == ' ') i++;
            int statusStart = i;
            while (i < len && Character.isDigit(line.charAt(i))) i++;
            if (i == statusStart) return null;
            int status = parseInt(line, statusStart, i);

            // --- ENDPOINT ---
            while (i < len && line.charAt(i) == ' ') i++;
            int epStart = i;
            while (i < len && line.charAt(i) != ' ') i++;
            if (i == epStart) return null;
            String endpoint = line.substring(epStart, i);

            // --- LATENCY (digits followed by "ms") ---
            while (i < len && line.charAt(i) == ' ') i++;
            int latStart = i;
            while (i < len && Character.isDigit(line.charAt(i))) i++;
            if (i == latStart) return null;
            int latency = parseInt(line, latStart, i);

            return new LogEntry(ts, level, status, endpoint, latency);
        } catch (RuntimeException e) {
            // Never let one bad line kill the whole pipeline.
            return null;
        }
    }

    private static int parseInt(String s, int from, int to) {
        int v = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    /**
     * Parses "2026-07-25 10:00:00.001" or "2026-07-25 10:00:00" (no ms).
     * Returns -1 on malformed input.
     *
     * Format breakdown (fixed positions):
     *   0-3   year   (4 digits)
     *   4     '-'    (separator)
     *   5-6   month  (2 digits)
     *   7     '-'
     *   8-9   day    (2 digits)
     *   10    ' '
     *   11-12 hour
     *   13    ':'
     *   14-15 minute
     *   16    ':'
     *   17-18 second
     *   19    '.'    (optional)
     *   20-22 millis (optional, 3 digits)
     */
    private static long parseTimestamp(String tsStr) {
        if (tsStr.length() < 19) return -1;

        int year  = parseInt(tsStr, 0, 4);
        int month = parseInt(tsStr, 5, 7);
        int day   = parseInt(tsStr, 8, 10);
        int hour  = parseInt(tsStr, 11, 13);
        int min   = parseInt(tsStr, 14, 16);
        int sec   = parseInt(tsStr, 17, 19);

        if (year < 0 || month < 0 || day < 0
                || hour < 0 || min < 0 || sec < 0) return -1;

        // Optional milliseconds
        int millis = 0;
        if (tsStr.length() >= 23 && tsStr.charAt(19) == '.') {
            int ms = parseInt(tsStr, 20, 23);
            if (ms < 0) return -1;
            millis = ms;
        }

        try {
            return LocalDateTime.of(year, month, day, hour, min, sec, millis * 1_000_000)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}