package com.smbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmBoxApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmBoxApplication.class, args);
    }
}
