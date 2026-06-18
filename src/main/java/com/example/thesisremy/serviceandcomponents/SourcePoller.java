package com.example.thesisremy.serviceandcomponents;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.example.thesisremy.model.Source;

/*
    Polls every enabled Source on its own schedule and broadcasts each new JSON
    payload to the glasses.

    Replaces the old DataGetter, which polled a single hardcoded URL once per
    second. The behaviour for the original Python AI source is preserved:
    the source with id LayoutStore.legacySourceId() is broadcast both as a
    NAMED SSE event (so the new Android flow can subscribe to it like any
    other source) AND as the UNNAMED "welddata" event the current Android
    app already listens for. Once the Android app is migrated the unnamed
    path can be removed.

    Scheduling uses a small ScheduledExecutorService rather than @Scheduled so
    that:
      - each source runs on its own cadence (pollMs)
      - sources can be added, edited, and removed at runtime without restarting
      - slow or hanging sources do not block other sources

    LayoutStore notifies us of every source-list change via onSourcesChanged().
    On notification we cancel the tasks for sources that disappeared, leave
    untouched the ones that are unchanged, and schedule the new or edited ones.

    Polling and streaming are still gated by the global toggles in ServerState:
      - pollingEnabled    : if false, the task is rescheduled but the HTTP
                            request is skipped, keeping the cadence steady.
      - streamingEnabled  : if false, the payload is fetched and deduplicated
                            but not broadcast to the glasses.
    These are intentionally global rather than per-source to keep the existing
    dashboard "Start Session" button semantics intact.
*/
@Component
public class SourcePoller {

    private final LayoutStore  layoutStore;
    private final Broadcast    broadcast;
    private final ServerState  serverState;
    private final RestTemplate restTemplate = new RestTemplate();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "source-poller");
                t.setDaemon(true);
                return t;
            });

    // One scheduled task per active source, keyed by source id. Mutated only
    // on the LayoutStore notification thread and on @PostConstruct.
    private final Map<String, Scheduled> active = new HashMap<>();

    public SourcePoller(LayoutStore layoutStore, Broadcast broadcast, ServerState serverState) {
        this.layoutStore = layoutStore;
        this.broadcast   = broadcast;
        this.serverState = serverState;
    }

    @PostConstruct
    public void start() {
        layoutStore.onSourcesChanged(this::reschedule);
        reschedule();
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    // Diff active vs requested, cancel removed/changed, schedule new/changed.
    private synchronized void reschedule() {
        List<Source>  desired       = layoutStore.getSources();
        Set<String>   desiredIds    = new HashSet<>();

        for (Source s : desired) {
            desiredIds.add(s.id);
            Scheduled current = active.get(s.id);
            if (current != null && current.matches(s)) continue; // unchanged
            if (current != null) current.future.cancel(false);
            scheduleSource(s);
        }

        // Cancel anything that is no longer in the desired list
        Set<String> stale = new HashSet<>(active.keySet());
        stale.removeAll(desiredIds);
        for (String id : stale) {
            Scheduled s = active.remove(id);
            if (s != null) s.future.cancel(false);
        }
    }

    private void scheduleSource(Source s) {
        if (!s.enabled || s.pollMs <= 0 || s.url == null || s.url.isBlank()) {
            active.remove(s.id);
            return;
        }
        Scheduled wrapped   = new Scheduled(snapshot(s), null, null);
        Runnable  task      = () -> pollOnce(wrapped);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                task, 0, s.pollMs, TimeUnit.MILLISECONDS);
        wrapped.future  = future;
        active.put(s.id, wrapped);
    }

    private void pollOnce(Scheduled wrapped) {
        Source s = wrapped.snapshot;

        if (!serverState.isPollingEnabled()) return;

        try {
            String json = restTemplate.getForObject(s.url, String.class);
            if (json == null) return;
            if (json.equals(wrapped.lastJson)) return; // dedup
            wrapped.lastJson = json;

            layoutStore.setLastPacket(s.id, json);

            // Legacy dashboard tile reads ServerState.lastDataPacket, which
            // historically meant "the most recent welder packet". Preserve
            // that by mirroring the legacy source into it.
            if (LayoutStore.legacySourceId().equals(s.id)) {
                serverState.setLastDataPacket(json);
            }

            if (!serverState.isStreamingEnabled()) return;

            // New flow: every source goes out as a named SSE event matching its id
            broadcast.broadcastNamed(s.id, json);

            // Backward compat: also send the legacy source as the unnamed
            // "welddata" event so the current Android app keeps working.
            if (LayoutStore.legacySourceId().equals(s.id)) {
                broadcast.broadcast(json);
            }
        } catch (RestClientException e) {
            System.err.println("[SourcePoller] " + s.id + " unreachable: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[SourcePoller] " + s.id + " unexpected error: " + e.getMessage());
        }
    }

    // Immutable snapshot of the source fields we care about, used so the
    // polling task is not affected by later edits on the LayoutStore copy.
    private static Source snapshot(Source s) {
        return new Source(s.id, s.name, s.url, s.pollMs, s.enabled);
    }

    // Internal record of one scheduled source. lastJson is mutated by the
    // single polling thread for this source, so no synchronization is needed
    // beyond the executor's happens-before guarantees.
    private static class Scheduled {
        final Source snapshot;
        ScheduledFuture<?> future;
        String lastJson;

        Scheduled(Source snapshot, ScheduledFuture<?> future, String lastJson) {
            this.snapshot = snapshot;
            this.future   = future;
            this.lastJson = lastJson;
        }

        boolean matches(Source other) {
            return snapshot.enabled == other.enabled
                && snapshot.pollMs  == other.pollMs
                && eq(snapshot.url,  other.url);
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }
}
