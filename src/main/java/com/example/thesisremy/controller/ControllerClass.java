package com.example.thesisremy.controller;



import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.thesisremy.serviceandcomponents.Broadcast;


/*

    You can say that class is the door of the server, clients can enter from here 
    and can can get added to the receiving list through making an instance of the Broadcast service.

 */


@RestController //This annotation tells spring that this class handles web requests
public class ControllerClass {
    private final Broadcast Broadcast;

    public ControllerClass(Broadcast Broadcast) {
        this.Broadcast = Broadcast;
    }

    @GetMapping("/stream")
    public SseEmitter stream() {
        return Broadcast.addEmitter();
    }
}