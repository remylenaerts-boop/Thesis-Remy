package com.example.thesisremy.serviceandcomponents;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/*
    Polls the Python AI program every second and broadcasts any new data to all connected SSE clients.

    Two safeguards are in place:
      - Deduplication: if the Python program returns the same JSON as last time, nothing is broadcast.
        This prevents flooding the glasses with redundant updates when no new welding data is available.
      - Error handling: if the Python program is unreachable, the exception is caught and logged once.
        The scheduler keeps running normally so it recovers automatically when Python comes back up.
*/
@Component
public class DataGetter {

    private final Broadcast broadcast;
    private final RestTemplate restTemplate = new RestTemplate();
    private static final String SOURCE_URL = "http://127.0.0.1:8060/api/v1/live";

    // Stores the last broadcast value so we can skip duplicate responses
    private String lastJson = null;

    public DataGetter(Broadcast broadcast) {
        this.broadcast = broadcast;
    }

    @Scheduled(fixedRate = 1000) // milliseconds
    public void pollAndBroadcast() {
        try {
            String json = restTemplate.getForObject(SOURCE_URL, String.class);

            // Only broadcast if the data has actually changed since the last poll
            if (json != null && !json.equals(lastJson)) {
                broadcast.broadcast(json);
                lastJson = json;
            }
        } catch (RestClientException e) {
            // Python program is down or unreachable — log and wait for next poll
            System.err.println("[DataGetter] Could not reach Python AI: " + e.getMessage());
        }
    }
}