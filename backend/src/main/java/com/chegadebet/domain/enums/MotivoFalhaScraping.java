package com.chegadebet.domain.enums;

/**
 * Por que uma pré-análise não produziu evidência.
 * <p>
 * Existe para separar duas coisas que um booleano juntaria: "olhamos a página e não
 * achamos nada" é diferente de "não conseguimos olhar a página". A primeira é evidência
 * fraca a favor do domínio; a segunda não é evidência de nada.
 * <p>
 * Nenhum motivo daqui mexe no score. Uma casa de aposta atrás de um WAF que devolve 403
 * para robô não é menos casa de aposta por causa disso, e um site legítimo com o
 * certificado vencido não é mais suspeito por causa disso.
 */
public enum MotivoFalhaScraping {

    /** O nome não resolve. Definitivo: repetir em dois segundos dá o mesmo resultado. */
    DNS_NAO_RESOLVE(false),

    /**
     * O host resolveu para um endereço que a pré-análise se recusa a acessar: rede
     * privada, loopback, link-local ou o endpoint de metadados de nuvem. Definitivo, e o
     * único motivo daqui que também é um alerta de segurança — ver {@code GuardaSsrf}.
     */
    SSRF_BLOQUEADO(false),

    /** A URL não é {@code http} nem {@code https}. Definitivo. */
    ESQUEMA_INVALIDO(false),

    /** Passou de {@code max-redirects} hops. Definitivo: o laço não se desfaz sozinho. */
    REDIRECT_EXCEDIDO(false),

    /**
     * Certificado inválido, vencido ou com nome que não casa. Definitivo por decisão:
     * <b>não</b> desligamos a validação de TLS para raspar. Ignorar o certificado de um
     * domínio hostil é abrir a porta para o intermediário que a validação existe para
     * fechar, e a evidência que ganharíamos não vale isso.
     */
    CERTIFICADO_INVALIDO(false),

    /**
     * O alvo respondeu 4xx. Definitivo <b>de propósito</b>, mesmo quando tecnicamente
     * daria para repetir: 403 e 429 são o alvo dizendo "não me raspe", e insistir seria
     * hostil com quem estamos analisando.
     */
    HTTP_4XX(false),

    /** O que veio não é HTML (PDF, imagem, JSON). Definitivo: não há o que casar. */
    CONTEUDO_NAO_HTML(false),

    /** Estourou o timeout de conexão ou o total. Transitório: rede ruim acontece. */
    TIMEOUT(true),

    /** Ninguém atendeu na porta. Transitório: pode ser reinício do outro lado. */
    CONEXAO_RECUSADA(true),

    /** O alvo respondeu 5xx. Transitório: erro do servidor costuma passar. */
    HTTP_5XX(true),

    /** A conexão caiu no meio da leitura. Transitório. */
    ERRO_DE_LEITURA(true);

    private final boolean transitoria;

    MotivoFalhaScraping(boolean transitoria) {
        this.transitoria = transitoria;
    }

    /**
     * Se vale repetir a tentativa agora.
     * <p>
     * A informação mora no enum, e não em um {@code switch} dentro do worker, porque
     * quem acrescentar um motivo novo é obrigado a responder a esta pergunta. Um
     * {@code default} em outro arquivo responderia por essa pessoa, e o motivo novo
     * herdaria silenciosamente o comportamento errado.
     */
    public boolean isTransitoria() {
        return transitoria;
    }
}
