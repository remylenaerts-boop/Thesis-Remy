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
    This is the front door of the server: all HTTP communication with the outside world
    starts here. Three endpoints are exposed:

      GET  /stream       : the Android glasses connect here to receive live welding data
                            over a persistent SSE connection (Server-Sent Events).
                            Two named event types are sent over this connection:
                              "welddata"     : Python AI JSON (current, voltage, gas flow ...)
                              "cameraControl": server → glasses command to start/stop camera

      GET  /frames/count : returns how many camera frames have been saved this session.

      POST /pool         : called by pool_detector.py with the latest detection result.
                            Result is stored for the dashboard but NOT forwarded to the glasses.
                            Pool tracking is only used for the annotated video recording.

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
        an SseEmitter: essentially an open channel through which the server can
        push data to the glasses at any time. The emitter is registered in Broadcast
        so that future data packets find their way to this specific connection.
    */
    @GetMapping("/stream")
    public SseEmitter stream() {
        return broadcast.addEmitter();
    }

    /*
        Returns the number of frames saved so far in the current session.
        Handy for quickly checking whether frames are coming in.
        Example response: { "framesSaved": 42 }
        This is also used in the dashboard.
    */
    @GetMapping("/frames/count")
    public ResponseEntity<String> frameCount() {
        int count = frameWebSocketHandler.getFrameCount();
        return ResponseEntity.ok("{ \"framesSaved\": " + count + " }");
    }

    /*
        Called by pool_detector.py every time it processes a new frame.
        The result is stored on the dashboard for monitoring only.
        It is NOT forwarded to the glasses: pool tracking is used exclusively
        for the annotated video recording, not as a live AR overlay.
    */
    @PostMapping("/pool")
    public ResponseEntity<Void> receivePoolDetection(@RequestBody String json) {
        serverState.setLastPoolResult(json);
        return ResponseEntity.ok().build();
    }
}

/*
To add some extra detail for the interested reader, the ResponsEntity is a wrapper made by Spring 
that lets you control the full HTTP response.

To make it very simple, picture it like a shipping box, and the data inside is for example
the String from /frames/count on line 66. The ResponseEntity is the box, this box has a label, status code (200- succes 404-not found)
this status code lets the receiver know if the delivery was succesful or not. 

The ResponseEntity is not really needed in this simple logic but is more for convention.
If in the future you would like to send another status code it it easily implementable. 
The documentation of this can be found here: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/ResponseEntity.html

Then very short about the Spring annotations

@RestController, Marks this class as the "front door" of your server. Any incoming web requests will be handled by the methods inside this class.
@GetMapping, Handles requests that fetch data, like loading a webpage or asking "how many frames are saved?". Think of it as answering a question.
@PostMapping, Handles requests that send data to the server, like submitting a form or uploading a frame. Think of it as receiving a delivery.

@RestController: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/bind/annotation/RestController.html
@GetMapping: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/bind/annotation/GetMapping.html
@PostMapping: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/bind/annotation/PostMapping.html
*/
