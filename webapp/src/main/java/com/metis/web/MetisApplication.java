package com.metis.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.metis")
@EnableScheduling
public class MetisApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetisApplication.class, args);
    }
}
