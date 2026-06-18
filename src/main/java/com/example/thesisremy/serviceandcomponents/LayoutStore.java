package com.example.thesisremy.serviceandcomponents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

import com.example.thesisremy.model.Layout;
import com.example.thesisremy.model.Source;
import com.example.thesisremy.model.Widget;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/*
    Central store for sources, layouts, and which layout is currently active.

    Two responsibilities:
      1) Hold the data in memory and keep config/layouts.json in sync with it.
         Every mutating method calls persist() before returning.
      2) Notify listeners (currently SourcePoller) whenever the source list
         changes so they can reschedule polling.

    Concurrency: all mutating methods are synchronized. Read methods return
    defensive copies. The lastPacketBySource map uses ConcurrentHashMap because
    it is written from the polling threads and read from the HTTP threads at
    high frequency.

    Persistence file shape (config/layouts.json):
      {
        "activeLayout": "default",
        "sources":      [ {Source}, ... ],
        "layouts":      [ {Layout}, ... ]
      }

    On first startup the file is missing, so we seed it with one source pointing
    at the existing Python AI URL and one "default" layout that mirrors the
    three gauges and heat bar shown by the current Android app.
*/
@Component
public class LayoutStore {

    private static final Path   CONFIG_PATH       = Path.of("config", "layouts.json");
    private static final String LEGACY_SOURCE_ID  = "welder1";

    // ── In-memory state (guarded by `this`) ──────────────────────────────────
    private final List<Source>      sources       = new ArrayList<>();
    private final Map<String, Layout> layouts     = new HashMap<>();
    private String                   activeLayout = "default";

    // Last JSON payload received from each source. Concurrent because writes
    // come from polling threads and reads come from HTTP threads.
    private final Map<String, String> lastPacketBySource = new ConcurrentHashMap<>();

    // Listeners notified after every source-list change (add/edit/delete).
    // SourcePoller registers itself here so it can reschedule its tasks.
    private final List<Runnable> sourcesChangedListeners = new CopyOnWriteArrayList<>();

    private final ObjectMapper mapper = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
            .enable(SerializationFeature.INDENT_OUTPUT);

    @PostConstruct
    public synchronized void init() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                Snapshot snap = mapper.readValue(Files.readAllBytes(CONFIG_PATH), Snapshot.class);
                if (snap.sources != null) sources.addAll(snap.sources);
                if (snap.layouts != null) for (Layout l : snap.layouts) layouts.put(l.name, l);
                if (snap.activeLayout != null) activeLayout = snap.activeLayout;
                System.out.println("[LayoutStore] Loaded " + sources.size() + " source(s) and "
                        + layouts.size() + " layout(s) from " + CONFIG_PATH);
                return;
            } catch (IOException e) {
                System.err.println("[LayoutStore] Could not read " + CONFIG_PATH + ": "
                        + e.getMessage() + " - reverting to defaults");
            }
        }
        seedDefaults();
        persist();
    }

    // Seed used when config/layouts.json does not exist yet. Mirrors the
    // hardcoded layout the current Android app shows so day-one behaviour is
    // unchanged after the migration.
    private void seedDefaults() {
        sources.add(new Source(LEGACY_SOURCE_ID, "Welder AI",
                ServerState.PYTHON_AI_URL, 1000, true));

        Layout def = new Layout("default");

        // Heat bar across the top
        Widget heat = new Widget();
        heat.id       = "heatbar";
        heat.type     = "heatbar";
        heat.label    = "POROSITY";
        heat.unit     = "";
        heat.source   = LEGACY_SOURCE_ID;
        heat.jsonPath = "inference_latest[*].prob_1";
        heat.scale    = 1.0;
        heat.offset   = 0.0;
        heat.min      = 0.0;
        heat.max      = 1.0;
        heat.x        = 20;
        heat.y        = 20;
        heat.w        = 880;
        heat.h        = 60;
        def.widgets.add(heat);

        // Three gauges below the heat bar. Scales match the hardcoded values
        // currently applied on the Android side (current*1000, voltage*5,
        // gasflow*5.31) so the operator sees the same numbers as before.
        def.widgets.add(gauge("current",  "CURRENT", "A",     "analog_rms_per_channel[3]",
                              1000.0, 0.0,   0.0, 400.0,  20, 120, 280, 280));
        def.widgets.add(gauge("voltage",  "VOLTAGE", "V",     "analog_rms_per_channel[4]",
                                 5.0, 0.0,   0.0,  40.0, 320, 120, 280, 280));
        def.widgets.add(gauge("gasflow",  "GASFLOW", "l/min", "analog_rms_per_channel[5]",
                              5.31, 0.0,   0.0,  20.0, 620, 120, 280, 280));

        layouts.put(def.name, def);
        activeLayout = def.name;
    }

    private static Widget gauge(String id, String label, String unit, String jsonPath,
                                double scale, double offset, double min, double max,
                                int x, int y, int w, int h) {
        Widget g = new Widget();
        g.id       = id;
        g.type     = "gauge";
        g.label    = label;
        g.unit     = unit;
        g.source   = LEGACY_SOURCE_ID;
        g.jsonPath = jsonPath;
        g.scale    = scale;
        g.offset   = offset;
        g.min      = min;
        g.max      = max;
        g.x        = x;
        g.y        = y;
        g.w        = w;
        g.h        = h;
        return g;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public synchronized List<Source> getSources() {
        return new ArrayList<>(sources);
    }

    public synchronized Optional<Source> getSource(String id) {
        return sources.stream().filter(s -> id.equals(s.id)).findFirst();
    }

    public synchronized void putSource(Source s) {
        sources.removeIf(existing -> existing.id.equals(s.id));
        sources.add(s);
        persist();
        notifySourcesChanged();
    }

    public synchronized boolean deleteSource(String id) {
        boolean removed = sources.removeIf(s -> s.id.equals(id));
        if (removed) {
            persist();
            notifySourcesChanged();
        }
        return removed;
    }

    public synchronized List<Layout> getLayouts() {
        return new ArrayList<>(layouts.values());
    }

    public synchronized Optional<Layout> getLayout(String name) {
        return Optional.ofNullable(layouts.get(name));
    }

    public synchronized void putLayout(Layout l) {
        layouts.put(l.name, l);
        persist();
    }

    public synchronized boolean deleteLayout(String name) {
        if (name.equals(activeLayout)) return false; // refuse to delete the active layout
        boolean removed = layouts.remove(name) != null;
        if (removed) persist();
        return removed;
    }

    public synchronized String getActiveLayoutName() {
        return activeLayout;
    }

    public synchronized Optional<Layout> getActiveLayout() {
        return Optional.ofNullable(layouts.get(activeLayout));
    }

    public synchronized boolean setActiveLayout(String name) {
        if (!layouts.containsKey(name)) return false;
        activeLayout = name;
        persist();
        return true;
    }

    // ── Last-packet cache, written by SourcePoller ──────────────────────────

    public void setLastPacket(String sourceId, String json) {
        lastPacketBySource.put(sourceId, json);
    }

    public String getLastPacket(String sourceId) {
        return lastPacketBySource.get(sourceId);
    }

    public Map<String, String> snapshotLastPackets() {
        return new HashMap<>(lastPacketBySource);
    }

    // ── Listener registration for SourcePoller ──────────────────────────────

    public void onSourcesChanged(Runnable listener) {
        sourcesChangedListeners.add(listener);
    }

    private void notifySourcesChanged() {
        for (Runnable r : sourcesChangedListeners) {
            try { r.run(); } catch (Exception e) {
                System.err.println("[LayoutStore] listener failed: " + e.getMessage());
            }
        }
    }

    public static String legacySourceId() {
        return LEGACY_SOURCE_ID;
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private void persist() {
        try {
            if (CONFIG_PATH.getParent() != null) Files.createDirectories(CONFIG_PATH.getParent());
            Snapshot snap = new Snapshot();
            snap.activeLayout = activeLayout;
            snap.sources      = new ArrayList<>(sources);
            snap.layouts      = new ArrayList<>(layouts.values());
            Files.write(CONFIG_PATH, mapper.writeValueAsBytes(snap));
        } catch (IOException e) {
            System.err.println("[LayoutStore] Could not write " + CONFIG_PATH + ": " + e.getMessage());
        }
    }

    // Shape persisted to disk: a flat object with the three top-level fields.
    public static class Snapshot {
        public String       activeLayout;
        public List<Source> sources;
        public List<Layout> layouts;
    }
}
