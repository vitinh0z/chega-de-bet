package com.chegadebet.domain.model;

import com.chegadebet.domain.enums.StatusDominio;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dominio")
@Getter
@Setter
@NoArgsConstructor
public class Dominio {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, unique = true, length = 253)
    private String host;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusDominio status;

    @Column(nullable = false)
    private int score;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant criadoEm;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant atualizadoEm;
}
