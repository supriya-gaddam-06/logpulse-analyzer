package com.logpulse;

public record LogEntry(
        long timestamp,
        String level,
        int status,
        String endpoint,
        int latencyMs
) {
    public boolean isError() {
        return status >= 400;
    }
}