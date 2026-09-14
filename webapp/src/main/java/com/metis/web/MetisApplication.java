package com.metis.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.metis")
public class MetisApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetisApplication.class, args);
    }
}
