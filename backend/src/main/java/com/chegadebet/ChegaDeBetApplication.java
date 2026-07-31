package com.chegadebet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: sem ela o @Scheduled do expurgo de tokens é ignorado em silêncio.
// @ConfigurationPropertiesScan: detecta os records de configuração (TokenProperties).
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ChegaDeBetApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChegaDeBetApplication.class, args);
    }
}
