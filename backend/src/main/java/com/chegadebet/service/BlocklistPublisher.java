package com.chegadebet.service;

import com.chegadebet.domain.model.Dominio;

/**
 * Ponto de extensão da publicação na blocklist.
 * <p>
 * O pipeline de verdade (commit no Git, build da lista, assinatura) ainda não existe. A
 * interface existe desde já para que a regra de negócio da moderação não precise mudar
 * quando ele chegar: troca-se a implementação, e {@link ModeracaoService} continua igual.
 * O teste injeta um dublê e verifica que a publicação acontece uma vez só.
 */
public interface BlocklistPublisher {

    void publicar(Dominio dominio);
}
