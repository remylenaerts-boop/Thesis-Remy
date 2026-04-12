package com.example.thesisremy.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.thesisremy.serviceandcomponents.Broadcast;
import com.example.thesisremy.serviceandcomponents.FrameWebSocketHandler;

/*
    This is the front door of the server — all HTTP communication with the outside world
    starts here. Two endpoints are exposed:

      GET /stream        — the Android glasses connect here to receive live welding data
                           over a persistent SSE connection (Server-Sent Events).

      GET /frames/count  — a simple diagnostic endpoint to check how many camera frames
                           have been received in the current session without checking the disk.

    Note: the WebSocket endpoint /frames is NOT defined here. It is registered separately
    in WebSocketConfig because WebSocket connections are handled differently from regular HTTP.
*/
@RestController
public class ControllerClass {

    private final Broadcast             broadcast;
    private final FrameWebSocketHandler frameWebSocketHandler;

    public ControllerClass(Broadcast broadcast,
                           FrameWebSocketHandler frameWebSocketHandler) {
        this.broadcast             = broadcast;
        this.frameWebSocketHandler = frameWebSocketHandler;
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
}
