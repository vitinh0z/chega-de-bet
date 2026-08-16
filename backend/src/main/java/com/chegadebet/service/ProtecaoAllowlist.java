package com.chegadebet.service;

import com.chegadebet.config.ModeracaoProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Decide se um host está sob a allowlist de proteção.
 * <p>
 * Existe como componente próprio porque a resposta é usada por dois caminhos que não se
 * conhecem — a moderação, que exige quórum maior para esses domínios, e a pré-análise,
 * que nem abre conexão para eles. Duas implementações da mesma regra divergiriam
 * exatamente no ponto sutil descrito em {@link #protege}, e a divergência apareceria como
 * um site de jornalismo raspado por engano, não como um erro.
 */
@Component
public class ProtecaoAllowlist {

    /** As entradas em minúsculas: casam o host exato. */
    private final String[] exatas;

    /**
     * As mesmas entradas já com o ponto na frente: casam os subdomínios.
     * <p>
     * Pré-montadas na construção porque a versão anterior fazia {@code "." + entrada}
     * <b>dentro</b> do laço — uma String nova por entrada, por host testado, em um método
     * chamado para cada domínio de cada ciclo do worker. A lista não muda em tempo de
     * execução, então essa concatenação tinha sempre o mesmo resultado.
     */
    private final String[] sufixos;

    public ProtecaoAllowlist(ModeracaoProperties properties) {
        List<String> normalizadas = properties.allowlist().stream()
                .map(entrada -> entrada.toLowerCase(Locale.ROOT))
                .toList();

        this.exatas = normalizadas.toArray(String[]::new);
        this.sufixos = normalizadas.stream().map(entrada -> "." + entrada).toArray(String[]::new);
    }

    /**
     * Se bloquear este host por engano causaria dano grave: jornalismo, saúde, apoio a
     * dependente químico, órgão público.
     * <p>
     * Casa o host exato e os subdomínios. O ponto antes da entrada não é detalhe: com
     * {@code endsWith(entrada)} puro, {@code malgov.br} casaria com {@code gov.br} e
     * ganharia uma proteção que não é dele.
     * <p>
     * Laço sobre array em vez de {@code stream().anyMatch()}: o método é chamado uma vez
     * por domínio da fila, e o stream monta um pipeline de objetos a cada chamada para
     * percorrer três entradas.
     */
    public boolean protege(String host) {
        String normalizado = host.toLowerCase(Locale.ROOT);
        for (int i = 0; i < exatas.length; i++) {
            if (normalizado.equals(exatas[i]) || normalizado.endsWith(sufixos[i])) {
                return true;
            }
        }
        return false;
    }
}
