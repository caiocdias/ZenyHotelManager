package com.zeny.hotel.files

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `files` (ARCHITECTURE §3.1 e §14, D14): anexos num armazenamento compatível
 * com S3 (MinIO no compose, S3/R2/B2 na nuvem), com os metadados no schema `files`, limites de
 * tamanho e tipo por finalidade, download só por URL assinada de curta duração e cifragem dos
 * anexos sensíveis, com auditoria de cada acesso a eles (§8.3, RNF-09). Depende só de `shared`; é
 * um serviço de plataforma, e qualquer módulo pode depender dele (regra por camadas, §3.1).
 *
 * A raiz do pacote é a API pública: [FilesApi] (o arquivo existe e é de uma finalidade; vínculo ao
 * registro dono), o catálogo de finalidades ([FilePurpose], [ModuleFilePurposes], [FileType]), o id
 * [FileId] e os códigos de erro ([FilesErrorCodes]).
 */
@PackageInfo
@ApplicationModule(displayName = "Files", allowedDependencies = ["shared"])
class FilesModule
