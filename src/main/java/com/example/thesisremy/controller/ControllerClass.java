package com.example.thesisremy.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.thesisremy.serviceandcomponents.Broadcast;
import com.example.thesisremy.serviceandcomponents.FrameWebSocketHandler;

/*
    You can say that this class is the door of the server, clients can enter from here
    and can get added to the receiving list through making an instance of the Broadcast service.

    /stream  → SSE endpoint, Android glasses receive JSON data from here
    /frames  → WebSocket endpoint, Android glasses send camera frames here
               (registered in WebSocketConfig, handled in FrameWebSocketHandler)
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

    // Existing SSE endpoint — unchanged
    // Android glasses connect here to receive JSON data from the server
    @GetMapping("/stream")
    public SseEmitter stream() {
        return broadcast.addEmitter();
    }

    // Returns how many frames have been saved so far
    // Useful to verify frames are arriving without checking the disk manually
    // Example: GET http://localhost:8080/frames/count  →  { "framesSaved": 42 }
    @GetMapping("/frames/count")
    public ResponseEntity<String> frameCount() {
        int count = frameWebSocketHandler.getFrameCount();
        return ResponseEntity.ok("{ \"framesSaved\": " + count + " }");
    }
}