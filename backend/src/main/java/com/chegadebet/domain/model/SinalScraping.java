package com.chegadebet.domain.model;

import com.chegadebet.domain.enums.MotivoFalhaScraping;
import com.chegadebet.domain.scraping.AssinaturaEncontrada;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Uma execução da pré-análise, guardada para auditoria.
 * <p>
 * Cada tentativa vira uma linha e nenhuma linha é sobrescrita. O custo é uma tabela que
 * cresce; o que se compra com ele é a única forma de responder <i>"por que este domínio
 * foi bloqueado em março?"</i> meses depois, com a evidência que o moderador tinha na
 * tela naquele dia — e não com o que o site mostra hoje.
 * <p>
 * O histórico também é o que permite ver um domínio mudar: um site que era limpo em
 * janeiro e apareceu com selo de licenciadora em junho trocou de dono ou de ramo, e essa
 * transição só existe se as duas medições continuarem no banco.
 *
 * <h2>Nada aqui identifica quem denunciou</h2>
 * A tabela liga uma medição a um <b>domínio</b>. Não há coluna de denunciante, de token
 * nem de origem, nem por caminho indireto: o {@code dominio_id} é público por natureza
 * (o host é público), e é o único vínculo. Isso é o que torna a tabela publicável no
 * dashboard de transparência sem uma etapa de anonimização.
 */
@Entity
@Table(name = "sinal_scraping")
@Getter
@Setter
@NoArgsConstructor
public class SinalScraping {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    // LAZY: a listagem do histórico de um domínio já sabe qual é o domínio. Com EAGER,
    // carregar 200 medições dispararia 200 selects para reler a mesma linha de dominio.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dominio_id", nullable = false)
    private Dominio dominio;

    /** Se a página pôde ser lida. Falso não quer dizer "limpo" — quer dizer "sem dado". */
    @Column(nullable = false)
    private boolean sucesso;

    /** Preenchido só quando {@link #sucesso} é falso. */
    @Enumerated(EnumType.STRING)
    @Column(name = "motivo_falha", length = 30)
    private MotivoFalhaScraping motivoFalha;

    /**
     * As evidências, em JSONB.
     * <p>
     * Uma tabela filha seria a escolha automática, e foi descartada: a lista é sempre
     * lida inteira junto com a medição, nunca em pedaços, e nada aqui se relaciona com
     * outra entidade. Uma tabela filha só acrescentaria um join a toda leitura.
     * <p>
     * JSONB, e não texto, porque o dashboard de transparência vai querer agregar por tipo
     * de sinal — {@code jsonb_array_elements} faz isso no banco, sem trazer todas as
     * medições para a aplicação para contar em memória.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<AssinaturaEncontrada> assinaturas = List.of();

    /** Quanto do documento desceu. Comparado ao teto, mostra quem está sendo cortado. */
    @Column(name = "bytes_baixados", nullable = false)
    private int bytesBaixados;

    /**
     * Onde a cadeia de redirecionamento parou.
     * <p>
     * Guardado inteiro por ser evidência de primeira: um domínio de aparência inocente
     * que termina em uma casa de aposta é exatamente o padrão de página de afiliado.
     */
    @Column(name = "url_final", length = 2048)
    private String urlFinal;

    /** Shell de SPA: HTML sem conteúdo, porque o conteúdo depende de JavaScript. */
    @Column(name = "documento_vazio", nullable = false)
    private boolean documentoVazio;

    /** Duração da tentativa. Em milissegundos porque é o que a métrica de latência usa. */
    @Column(name = "duracao_ms", nullable = false)
    private long duracaoMs;

    @CreationTimestamp
    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;
}
