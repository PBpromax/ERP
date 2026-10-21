package com.company.oms.common;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unified document number generator.
 * Production systems can replace the in-memory counter with Redis INCR or a database sequence table.
 */
@Component
public class CodeGenerator {
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public String next(String prefix) {
        String ym = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = prefix + ym;
        int seq = counters.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
        return prefix + ym + "-" + String.format("%05d", seq);
    }
}
