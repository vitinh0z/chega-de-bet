package com.chegadebet.domain.model;

import com.chegadebet.domain.enums.CategoriaAposta;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

// Regra permanente: esta entidade NUNCA armazena dado pessoal nem histórico de navegação.
@Entity
@Table(name = "denuncia")
@Getter
@Setter
@NoArgsConstructor
public class Denuncia {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CategoriaAposta categoriaAposta;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant criadoEm;

    // LAZY: listar a fila de moderação não pode disparar um SELECT por denúncia
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dominio_id", nullable = false)
    private Dominio dominio;

    // Pseudônimo anônimo de quem denunciou: hash do token efêmero no momento da denúncia.
    // NUNCA é dado pessoal — valor opaco, usado só para contar denunciantes distintos (anti-sabotagem).
    @Column(name = "denunciante_hash", nullable = false, length = 64)
    private String denuncianteHash;
}
