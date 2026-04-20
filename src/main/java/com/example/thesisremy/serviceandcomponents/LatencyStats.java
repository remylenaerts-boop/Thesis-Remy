package com.example.thesisremy.serviceandcomponents;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LatencyStats {

    private final String name;
    private final int reportEvery;
    private final List<Long> samples = new ArrayList<>();
    private final String csvPath;

    public LatencyStats(String name, int reportEvery) {
        this.name        = name;
        this.reportEvery = reportEvery;
        this.csvPath     = "latency_" + name + ".csv";
        initCsv();
    }

    public void record(long ms) {
        samples.add(ms);
        writeCsvRow(samples.size(), ms);
        if (samples.size() % reportEvery == 0) printStats();
    }

    private void initCsv() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(csvPath, false))) {
            pw.println("sample,latency_ms");
        } catch (IOException e) {
            System.err.println("[LatencyStats] Could not create " + csvPath + ": " + e.getMessage());
        }
    }

    private void writeCsvRow(int index, long ms) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(csvPath, true))) {
            pw.println(index + "," + ms);
        } catch (IOException e) {
            System.err.println("[LatencyStats] Could not write to " + csvPath + ": " + e.getMessage());
        }
    }

    private void printStats() {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int    n      = sorted.size();
        double avg    = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        long   min    = sorted.get(0);
        long   max    = sorted.get(n - 1);
        long   median = sorted.get(n / 2);
        long   p95    = sorted.get((int) Math.ceil(n * 0.95) - 1);
        double stddev = Math.sqrt(sorted.stream().mapToDouble(v -> Math.pow(v - avg, 2)).average().orElse(0));

        System.out.printf(
            "[LATENCY-STATS][%s] n=%d  avg=%.1fms  median=%dms  min=%dms  max=%dms  p95=%dms  stddev=%.1fms%n",
            name, n, avg, median, min, max, p95, stddev
        );
    }
}
