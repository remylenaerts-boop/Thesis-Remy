package com.example.thesisremy.serviceandcomponents;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/*
    This service manages all active SSE connections and is responsible for
    pushing data out to the Android glasses.

    SSE (Server-Sent Events) is a one-way communication channel: the server
    pushes updates to the client over a regular HTTP connection that is kept open.
    It is simpler than WebSocket for this use case because we only need to send
    data in one direction — from server to glasses.

    Every time a glasses device connects via GET /stream, it gets its own SseEmitter
    object. All active emitters are kept in a list. When new welding data arrives,
    broadcast() loops through the list and sends the data to each connected device.
*/
@Service
public class Broadcast {

    /*
        The list of all currently connected clients.

        CopyOnWriteArrayList is used here instead of a regular ArrayList because
        connections can be added or removed at the same time as data is being sent
        (concurrency). A regular ArrayList is not safe in that situation and can
        throw exceptions or corrupt the list.

        CopyOnWriteArrayList solves this by making a fresh copy of the list every
        time it is modified. Reads (like looping during broadcast) always see a
        stable snapshot, so no crash can occur.
    */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /*
        Called by ControllerClass when a new client connects to GET /stream.
        Creates a new emitter for that client and registers three cleanup callbacks
        so the emitter is automatically removed from the list when the connection ends —
        whether that happens normally, due to an error, or because of a timeout.
    */
    public SseEmitter addEmitter() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // no timeout — keep the connection open indefinitely
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter)); // client disconnected cleanly
        emitter.onError(e    -> emitters.remove(emitter));   // connection dropped unexpectedly
        emitter.onTimeout(() -> emitters.remove(emitter));   // connection timed out
        return emitter;
    }

    /*
        Sends a JSON string to every connected client.

        removeIf() is used here as a convenient way to send and clean up in one pass:
        if sending to a client fails (IOException), it means that client has gone away
        and we return true to have it removed from the list. If sending succeeds we
        return false and it stays in the list.
    */
    public void broadcast(String json) {
        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().data(json));
                return false; // send succeeded — keep this client in the list
            } catch (IOException ignored) {
                emitter.complete();
                return true;  // send failed — remove this client from the list
            }
        });
    }
}
