package com.example.thesisremy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
    Entry point of the Spring Boot application.

    @SpringBootApplication tells Spring to scan all classes in this package and
    set up the entire application context automatically — no manual configuration needed.

    @EnableScheduling activates support for @Scheduled methods. Without this annotation,
    the DataGetter polling loop would simply never run.
*/
@SpringBootApplication
@EnableScheduling
public class ThesisRemyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThesisRemyApplication.class, args);
    }
}
