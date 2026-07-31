package com.chegadebet;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Pública porque os testes de outros pacotes (ex.: web) também sobem o contexto real.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer postgresContainer() {
        // No Testcontainers 2.0 os *Container deixaram de ser genéricos (sem <>)
        return new PostgreSQLContainer("postgres:18.4");
    }
}
