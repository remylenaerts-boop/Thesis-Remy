package com.example.thesisremy.serviceandcomponents;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/*
    Records latency measurements (in milliseconds) for thesis benchmarking.

    One instance is created per measurement point in the system:
        - DataGetter            how long the Python AI took to answer
        - Broadcast             how long it took to push an SSE event to all glasses
        - FrameWebSocketHandler how long it took to write a JPEG frame to disk

    Each instance:
        1. Writes every sample to its own CSV file (latency_<name>.csv) so the
           raw data can be analysed afterwards in Excel/Python.
        2. Every <reportEvery> samples, prints a summary line to the console
           (avg, median, min, max, p95, stddev) so progress is visible live.

    The CSV files are overwritten at server startup, each session produces a
    fresh dataset.
*/
public class LatencyStats {

    private final String name;                          // Used in the CSV filename and console tag
    private final int reportEvery;                      // Print summary every N samples
    private final List<Long> samples = new ArrayList<>();
    private final String csvPath;                       // e.g. "latency_frame.csv"

    public LatencyStats(String name, int reportEvery) {
        this.name        = name;
        this.reportEvery = reportEvery;
        this.csvPath     = "latency_" + name + ".csv";
        initCsv();
    }

    // Called once per measurement, store, append to CSV, and print stats every N samples
    public void record(long ms) {
        samples.add(ms);
        writeCsvRow(samples.size(), ms);
        if (samples.size() % reportEvery == 0) printStats();
    }

    // Wipes any old CSV from a previous run and writes the header row
    private void initCsv() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(csvPath, false))) {
            pw.println("sample,latency_ms");
        } catch (IOException e) {
            System.err.println("[LatencyStats] Could not create " + csvPath + ": " + e.getMessage());
        }
    }

    // Appends a single sample row to the CSV (sample number, latency in ms)
    private void writeCsvRow(int index, long ms) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(csvPath, true))) {
            pw.println(index + "," + ms);
        } catch (IOException e) {
            System.err.println("[LatencyStats] Could not write to " + csvPath + ": " + e.getMessage());
        }
    }

    /*
        Computes summary statistics over all samples seen so far and prints
        them to the console. Sorting a copy each time is fine, the lists are
        small (at most a few thousand samples per session) and this only
        runs every <reportEvery> samples.
    */
    private void printStats() {
        List<Long> sorted = new ArrayList<>(samples);   // Copy so we don't reorder the original
        Collections.sort(sorted);
        int    n      = sorted.size();
        double avg    = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        long   min    = sorted.get(0);                  // First after sort = smallest
        long   max    = sorted.get(n - 1);              // Last after sort = largest
        long   median = sorted.get(n / 2);              // Middle value
        long   p95    = sorted.get((int) Math.ceil(n * 0.95) - 1);   // 95th percentile, only 5% are slower
        double stddev = Math.sqrt(sorted.stream().mapToDouble(v -> Math.pow(v - avg, 2)).average().orElse(0));

        System.out.printf(
            "[LATENCY-STATS][%s] n=%d  avg=%.1fms  median=%dms  min=%dms  max=%dms  p95=%dms  stddev=%.1fms%n",
            name, n, avg, median, min, max, p95, stddev
        );
    }
}
