package com.example.thesisremy.serviceandcomponents;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/*
    Polls the Python AI program every second and broadcasts any new data to all connected SSE clients.

    Three safeguards are in place:
      - Toggle: if pollingEnabled is false in ServerState, the method returns immediately.
      - Deduplication: if the data has not changed since the last poll, nothing is broadcast.
      - Error handling: if the Python program is unreachable, the exception is caught and logged.
        The scheduler keeps running so it recovers automatically when Python comes back up.

    The streaming toggle is also respected — polling can run while streaming is paused,
    keeping the deduplication state up to date without sending anything to the glasses.
*/
@Component
public class DataGetter {

    private final Broadcast    broadcast;
    private final ServerState  serverState;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String SOURCE_URL = "http://127.0.0.1:8060/api/v1/live";

    // Stores the last received value so duplicate responses are not rebroadcast
    private String lastJson = null;

    public DataGetter(Broadcast broadcast, ServerState serverState) {
        this.broadcast   = broadcast;
        this.serverState = serverState;
    }

    @Scheduled(fixedRate = 1000) // milliseconds
    public void pollAndBroadcast() {

        // Polling toggle — stop fetching from Python entirely when disabled
        if (!serverState.isPollingEnabled()) return;

        try {
            String json = restTemplate.getForObject(SOURCE_URL, String.class);

            if (json != null && !json.equals(lastJson)) {
                lastJson = json;
                serverState.setLastDataPacket(json); // expose to dashboard overview

                // Streaming toggle — fetch and deduplicate, but don't send to glasses when disabled
                if (serverState.isStreamingEnabled()) {
                    broadcast.broadcast(json);
                }
            }
        } catch (RestClientException e) {
            System.err.println("[DataGetter] Could not reach Python AI: " + e.getMessage());
        }
    }
}
