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

    private static final String SOURCE_URL = ServerState.PYTHON_AI_URL;

    private String lastJson = null;

    private final LatencyStats pollStats      = new LatencyStats("poll",      100);
    private final LatencyStats broadcastStats = new LatencyStats("broadcast", 100);

    public DataGetter(Broadcast broadcast, ServerState serverState) {
        this.broadcast   = broadcast;
        this.serverState = serverState;
    }

    @Scheduled(fixedRate = 1000) // milliseconds
    public void pollAndBroadcast() {

        // Polling toggle — stop fetching from Python entirely when disabled
        if (!serverState.isPollingEnabled()) return;

        try {
            long t0   = System.nanoTime();
            String json = restTemplate.getForObject(SOURCE_URL, String.class);
            long fetchMs = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("[LATENCY][poll] pythonFetch=%dms%n", fetchMs);
            pollStats.record(fetchMs);

            if (json != null && !json.equals(lastJson)) {
                lastJson = json;
                serverState.setLastDataPacket(json); // expose to dashboard overview

                // Streaming toggle — fetch and deduplicate, but don't send to glasses when disabled
                if (serverState.isStreamingEnabled()) {
                    long t1 = System.nanoTime();
                    broadcast.broadcast(json);
                    long broadcastMs = (System.nanoTime() - t1) / 1_000_000;
                    System.out.printf("[LATENCY][broadcast] sseToClients=%dms%n", broadcastMs);
                    broadcastStats.record(broadcastMs);
                }
            }
        } catch (RestClientException e) {
            System.err.println("[DataGetter] Could not reach Python AI: " + e.getMessage());
        }
    }
}
