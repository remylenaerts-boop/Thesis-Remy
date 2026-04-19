package com.example.thesisremy.serviceandcomponents;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/*
    This service manages all active SSE connections and pushes data to the glasses.

    SSE (Server-Sent Events) is a one-way channel: the server pushes updates to the
    client over a persistent HTTP connection. Each connected glasses device gets its
    own SseEmitter object, kept in a shared list.

    Ghost connection problem: when a Wi-Fi connection drops abruptly, Spring does not
    always fire the onError/onCompletion callbacks. The emitter stays in the list as a
    ghost and the dashboard shows too many connected clients. A scheduled heartbeat
    every 30 seconds sends a small comment to all emitters — any dead connection throws
    an IOException and is removed immediately.
*/
@Service
public class Broadcast {

    /*
        Thread-safe list of all currently connected SSE clients.
        CopyOnWriteArrayList ensures that looping during a broadcast is always safe,
        even if a connection is added or removed at the same moment.
    */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /*
        Called by ControllerClass when a new client connects to GET /stream.
        Three cleanup callbacks ensure the emitter is removed when the connection ends,
        whether it closes cleanly, drops with an error, or times out.
    */
    public SseEmitter addEmitter() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onError(e    -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        System.out.println("[SSE] Client connected — active connections: " + emitters.size());
        return emitter;
    }

    /*
        Sends welding data as an UNNAMED SSE event.
        Unnamed events are received by the standard onMessage() handler in Android —
        keeping this unnamed means the existing Android app works without any changes.
    */
    public void broadcast(String json) {
        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().data(json));
                return false;
            } catch (IOException ignored) {
                emitter.complete();
                return true;
            }
        });
    }

    /*
        Sends a JSON string as a NAMED SSE event.
        The Android app can listen for a specific event name and ignore all others.

        Currently used event name:
          "cameraControl" — start/stop command sent to the glasses when camera is toggled
    */
    public void broadcastNamed(String eventName, String json) {
        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
                return false;
            } catch (IOException ignored) {
                emitter.complete();
                return true;
            }
        });
    }

    /*
        Heartbeat — runs every 30 seconds.
        Sends an SSE comment (a line starting with ':') to every emitter.
        SSE comments carry no data and are ignored by clients, but they cause
        an IOException on any connection that has silently died. Those dead
        emitters are removed from the list, keeping the count accurate.
    */
    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
                return false;
            } catch (IOException ignored) {
                emitter.complete();
                return true;
            }
        });
        if (!emitters.isEmpty()) {
            System.out.println("[SSE] Heartbeat — active connections: " + emitters.size());
        }
    }

    // Used by the dashboard status panel
    public int getConnectedCount() {
        return emitters.size();
    }
}
