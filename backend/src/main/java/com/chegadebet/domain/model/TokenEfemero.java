package com.chegadebet.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

// Identidade anônima para rate-limit. Sem qualquer vínculo com identidade real ou navegação.
@Entity
@Table(name = "token_efemero")
@Getter
@Setter
@NoArgsConstructor
public class TokenEfemero {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String valorHash;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant criadoEm;

    @Column(nullable = false)
    private Instant expiraEm;
}
