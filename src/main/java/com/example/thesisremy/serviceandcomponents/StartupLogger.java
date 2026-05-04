package com.example.thesisremy.serviceandcomponents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
    Prints a short summary to the console when the server finishes starting up.
    This makes it easy to confirm that the server is running and to see the
    URL the glasses should connect to, without having to dig through Spring's
    own startup logs.
*/
@Configuration
public class StartupLogger {

    // These values are read from application.properties at startup
    @Value("${server.port}")
    private String port;

    @Value("${server.name:sse-server}") // falls back to "sse-server" if not set in application.properties
    private String serverName;

    /*
        ApplicationRunner runs once, right after the application has fully started.
        It is the right place for any "we are ready" logging.
    */
    @Bean
    public ApplicationRunner logStartup() {
        return args -> {
            System.out.println("======================================");
            System.out.println("  AR Welding Server : READY");
            System.out.println("======================================");
            System.out.println("  Dashboard:    http://localhost:"    + port + "/dashboard");
            System.out.println("======================================");
        };
    }
}
