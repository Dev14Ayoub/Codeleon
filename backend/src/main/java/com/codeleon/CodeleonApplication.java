package com.codeleon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CodeleonApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeleonApplication.class, args);
    }
}
