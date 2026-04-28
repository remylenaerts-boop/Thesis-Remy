package com.example.thesisremy.serviceandcomponents;

import java.io.IOException;
import java.net.InetAddress;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/*
    Registers the server on the local network under the name "sse-server"
    so the Android glasses can find it by name instead of by IP address.

    The problem this solves: every time the server computer connects to Wi-Fi
    it may get a different IP address (192.168.1.42 today, 192.168.1.67 tomorrow).
    Hardcoding an IP address in the glasses app would annoying to change every time this happens.

    The solution is mDNS (multicast DNS), the same technology that lets you find
    a printer or Chromecast on your home network by name. The server broadcasts its
    presence on the local network, and any device that asks "where is sse-server.local?"
    gets the current IP address back automatically.

    After this runs, the glasses can always connect to sse-server.local:9999/stream
    regardless of what IP address the server has at that moment.

    Note for Windows users: the firewall must allow inbound UDP on port 5353 for mDNS
    to work. See the README for the one-time setup step.
*/
@Component
public class DnsConfig {

    /*
        @PostConstruct means this method runs automatically once Spring has finished
        setting up all components. This is the right place for any startup logic
        that needs the full application to be ready first.
    */
    @PostConstruct
    public void registerService() throws IOException {
        JmDNS jmdns = JmDNS.create(InetAddress.getLocalHost());

        // Describe the service: it is an HTTP service, reachable on port 9999, at path /stream
        ServiceInfo serviceInfo = ServiceInfo.create(
                "_http._tcp.local.",
                "sse-server",
                9999,
                "path=/stream"
        );

        jmdns.registerService(serviceInfo);
        System.out.println("[mDNS] Server registered as: sse-server.local");
    }
}
