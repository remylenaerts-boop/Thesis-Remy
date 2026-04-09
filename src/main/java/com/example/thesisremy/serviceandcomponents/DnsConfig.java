package com.example.thesisremy.serviceandcomponents;

import java.io.IOException;
import java.net.InetAddress;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;


/*

This Component class makes the server reachable to the name sse-server so that we have abstraction from the IP adress. 
to check the connection in your browser type this : http://localhost:9999/stream 
You cant look up http://sse-server:9999/stream on windows, only linux and macOS support this, you do need to add .local after sse-server to check on the same device

*/
@Component
public class DnsConfig {

    // https://docs.spring.io/spring-framework/reference/core/beans/annotation-config/postconstruct-and-predestroy-annotations.html
    @PostConstruct // this annotated method is invoked automatically once all the beans and its dependencies are injected
    public void registerService() throws IOException {
        JmDNS jmdns = JmDNS.create(InetAddress.getLocalHost());

        ServiceInfo serviceInfo = ServiceInfo.create(
                "_http._tcp.local.",
                "sse-server",
                9999,
                "path=/stream"
        );

        jmdns.registerService(serviceInfo);
        System.out.println("Server: sse-server.local");
    }
}
