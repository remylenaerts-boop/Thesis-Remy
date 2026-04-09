package com.example.thesisremy.serviceandcomponents;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/*

    This class reads the port where the data from the python AI program that gathers the data of the WAAM process
    and calculates the defect chance. It is marked as a @Component because it works on its own, 
    the pollAndBroadcast executes every second because of the @Scheduled.

*/
@Component
public class DataGetter {

    private final Broadcast Broadcast;
    private final RestTemplate restTemplate = new RestTemplate();
    private static final String SOURCE_URL = "http://127.0.0.1:8060/api/v1/live";

    public DataGetter(Broadcast Broadcast) {
        this.Broadcast = Broadcast;
    }

    @Scheduled(fixedRate = 1000) // milliseconds
    public void pollAndBroadcast() {
        String json = restTemplate.getForObject(SOURCE_URL, String.class);
        if (json != null) {
            //System.out.println(json);
            //System.out.println(json);
            //System.out.println(json);
            //System.out.println(json);
            Broadcast.broadcast(json);
        }
    }
}