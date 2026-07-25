package com.chegadebet.mapper;

import com.chegadebet.domain.model.Denuncia;
import com.chegadebet.web.dto.DenunciaRequest;
import com.chegadebet.web.dto.DenunciaResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface DenunciaMapper {


    @Mapping(target = "host", source = "dominio.host")
    @Mapping(target = "status", source = "dominio.status")
    DenunciaResponse toResponse(Denuncia denuncia);


    @Mapping(target = "categoriaAposta", source = "categoria")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "criadoEm", ignore = true)
    @Mapping(target = "denuncianteHash", ignore = true)
    @Mapping(target = "dominio", ignore = true)
    Denuncia toEntity(DenunciaRequest request);

}
