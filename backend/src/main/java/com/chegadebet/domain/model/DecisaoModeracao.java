package com.chegadebet.domain.model;

import com.chegadebet.domain.enums.TipoDecisao;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

// Voto de um moderador sobre um domínio: quem, quando, o quê e por quê.
// É a trilha de auditoria da moderação e a base de contagem do quórum.
@Entity
@Table(name = "decisao_moderacao")
@Getter
@Setter
@NoArgsConstructor
public class DecisaoModeracao {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    // LAZY: contar votos não precisa carregar o domínio inteiro.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dominio_id", nullable = false)
    private Dominio dominio;

    // Identidade de quem decidiu. Hoje vem do header X-Moderador, que o cliente declara;
    // quando houver Spring Security, passa a vir da identidade autenticada.
    @Column(nullable = false, length = 120)
    private String moderador;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TipoDecisao decisao;

    // Obrigatório na rejeição (garantido por CHECK no banco), opcional na aprovação.
    @Column(length = 500)
    private String motivo;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant criadoEm;
}
