package com.example.thesisremy.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.thesisremy.serviceandcomponents.Broadcast;
import com.example.thesisremy.serviceandcomponents.FrameWebSocketHandler;
import com.example.thesisremy.serviceandcomponents.ServerState;

/*
    This is the front door of the server — all HTTP communication with the outside world
    starts here. Three endpoints are exposed:

      GET  /stream        — the Android glasses connect here to receive live welding data
                            over a persistent SSE connection (Server-Sent Events).
                            Three named event types are sent over this connection:
                              "welddata"      — Python AI JSON (current, voltage, gas flow ...)
                              "pooldetection" — weld pool coordinates from pool_detector.py
                              "cameraControl" — server → glasses command to start/stop camera

      GET  /frames/count  — returns how many camera frames have been saved this session.

      POST /pool          — called by pool_detector.py with the latest detection result.
                            The server immediately rebroadcasts it to all glasses as
                            a named SSE event "pooldetection".

    Note: the WebSocket endpoint /frames is NOT defined here. It is registered separately
    in WebSocketConfig because WebSocket connections are handled differently from regular HTTP.
*/
@RestController
public class ControllerClass {

    private final Broadcast             broadcast;
    private final FrameWebSocketHandler frameWebSocketHandler;
    private final ServerState           serverState;

    public ControllerClass(Broadcast broadcast,
                           FrameWebSocketHandler frameWebSocketHandler,
                           ServerState serverState) {
        this.broadcast             = broadcast;
        this.frameWebSocketHandler = frameWebSocketHandler;
        this.serverState           = serverState;
    }

    /*
        When the glasses make a GET request to /stream, this method hands them
        an SseEmitter — essentially an open channel through which the server can
        push data to the glasses at any time. The emitter is registered in Broadcast
        so that future data packets find their way to this specific connection.
    */
    @GetMapping("/stream")
    public SseEmitter stream() {
        return broadcast.addEmitter();
    }

    /*
        Returns the number of frames saved so far in the current session.
        Handy for quickly checking during a demo whether frames are coming in.
        Example response: { "framesSaved": 42 }
    */
    @GetMapping("/frames/count")
    public ResponseEntity<String> frameCount() {
        int count = frameWebSocketHandler.getFrameCount();
        return ResponseEntity.ok("{ \"framesSaved\": " + count + " }");
    }

    /*
        Called by pool_detector.py every time it processes a new frame.
        The raw JSON body (poolDetected, poolX, poolY, poolRadius) is forwarded
        immediately to all connected glasses as a named SSE event "pooldetection".

        The glasses can listen specifically for this event name and ignore welddata,
        keeping the two data streams completely independent on the same connection.
    */
    @PostMapping("/pool")
    public ResponseEntity<Void> receivePoolDetection(@RequestBody String json) {
        // Tracking toggle — accept the POST but don't forward to glasses when disabled
        if (serverState.isTrackingEnabled()) {
            broadcast.broadcastNamed("pooldetection", json);
            serverState.setLastPoolResult(json);
        }
        return ResponseEntity.ok().build();
    }
}
