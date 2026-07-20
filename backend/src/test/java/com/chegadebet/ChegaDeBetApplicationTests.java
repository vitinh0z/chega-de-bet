package com.chegadebet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChegaDeBetApplicationTests {

    @Test
    void contextLoads() {
        // Sobe o contexto Spring com um PostgreSQL efêmero (Testcontainers).
    }
}
