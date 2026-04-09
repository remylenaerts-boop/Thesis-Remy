package com.example.thesisremy.serviceandcomponents;



import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service //  Makes this class a service
public class Broadcast {

    /*

    Every client that entered though the Controller door gets an instance of this and gets added to the List.

    Declares a List of SseEmitters, because there can be multiple people joining or leaving. 
    We need a thread safe list implementation because of concurrency --> multiple tasks (the joining or leaving) happening at the same time.
    The List<SseEmitter> has the thread safe implementation, like the name of CopyOnWriteArrayList implies, 
    when the array list changes it makes a fresh copy of the list and applies the changes. This way we can always read a stable version of the list.

     */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>(); 


    // Method to add clients to the emitter list and remove them when needed
    public SseEmitter addEmitter() {
        
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        return emitter;

    }

    // Method for the broadcasting of the data to all clients in the list
        // We have to use the try and catch to know if clients are still there
    public void broadcast(String json) {
        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().data(json)); //.event() from the SseEmitter API, you can still add ID, name ... just check the documentation (at the bottom you can see the interface)
                return false;
            } catch (IOException ignored) {
                emitter.complete();
                return true;
            }
        });
    }
}