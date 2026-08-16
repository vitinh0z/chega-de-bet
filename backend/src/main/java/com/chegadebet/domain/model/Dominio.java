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

    /**
     * Quando a última pré-análise deste domínio aconteceu, com ou sem sucesso.
     * <p>
     * {@code null} significa "nunca raspado", e é quem tem prioridade na fila: é onde a
     * pré-análise ainda pode dizer algo novo ao moderador.
     * <p>
     * É um dado derivado de {@code sinal_scraping}, duplicado aqui de propósito. Buscá-lo
     * por subconsulta forçava a fila {@code EM_ANALISE} inteira a ser materializada e
     * agregada a cada ciclo, porque a chave de ordenação não existia em índice nenhum.
     * Com a coluna, filtro e ordenação saem de {@code ix_dominio_fila_pre_analise}.
     * <p>
     * Quem grava um {@code SinalScraping} tem a obrigação de atualizar este campo na mesma
     * transação — ver {@code RegistroPreAnalise}.
     */
    @Column(name = "ultima_pre_analise_em")
    private Instant ultimaPreAnaliseEm;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant criadoEm;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant atualizadoEm;
}
