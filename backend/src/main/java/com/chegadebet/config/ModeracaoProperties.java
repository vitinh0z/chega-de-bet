package com.chegadebet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuração da moderação.
 *
 * @param quorumMinimo     quantos moderadores DISTINTOS precisam aprovar um domínio
 *                         sensível. Domínio comum aprova com um voto.
 * @param allowlist        domínios de proteção: bloqueá-los por engano causa dano grave
 *                         (jornalismo, saúde, apoio a dependente químico, órgão público).
 *                         Casa o host exato e os subdomínios.
 * @param limiarReabertura quantos denunciantes distintos NOVOS, depois de uma rejeição,
 *                         devolvem o domínio à fila de análise.
 */
@ConfigurationProperties(prefix = "chegadebet.moderacao")
public record ModeracaoProperties(int quorumMinimo, List<String> allowlist, int limiarReabertura) {
}
