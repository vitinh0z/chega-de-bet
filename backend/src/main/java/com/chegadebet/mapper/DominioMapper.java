package com.chegadebet.mapper;

import com.chegadebet.domain.model.Dominio;
import com.chegadebet.web.dto.DominioResponse;
import org.mapstruct.Mapper;

// id, host, status, score e criadoEm têm o mesmo nome/tipo nos dois lados,
// então o MapStruct mapeia tudo sozinho — nenhum @Mapping é necessário aqui.
@Mapper(componentModel = "spring")
public interface DominioMapper {

    DominioResponse toResponse(Dominio dominio);
}
