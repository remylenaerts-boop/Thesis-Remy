package com.example.thesisremy.serviceandcomponents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StartupLogger {
@Value("${server.port}")
    private String port;

    @Value("${server.name:sse-server}")
    // default = "sse-server" if not defined
    private String serverName;

    @Bean
    public ApplicationRunner logStartup() {
        return args -> {
            System.out.println("======================================");
            System.out.println("Server is running on port " + port);
            System.out.println("Server name: " + serverName);
            System.out.println("URL: http://" + serverName + ":" + port + "/stream");
            System.out.println("======================================");
        };
    }
}