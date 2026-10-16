package com.zeny.hotel.checklists

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `checklists` (ARCHITECTURE §3.1, SPEC §5.15 e §8.2.11; camada Plataforma): o motor
 * genérico de checklists usado por governança, manutenção, áreas comuns, restaurante e eventos. Pode depender
 * de `shared`, `hotel`, `staff` e `files`; não conhece UHs, estoque, rouparia nem manutenção: tipos de UH,
 * tipos de limpeza, áreas, ativos, insumos e tipos de enxoval são IDs opacos (H3 do roadmap do M2).
 *
 * A raiz do pacote é a API pública: [ChecklistsApi] (escolher o modelo de um sujeito e ler uma versão
 * publicada), os ids, o conteúdo de um modelo ([ChecklistContent]), os códigos de erro
 * ([ChecklistsErrorCodes]) e as permissões ([ChecklistsPermissions]).
 */
@PackageInfo
@ApplicationModule(displayName = "Checklists", allowedDependencies = ["shared", "hotel", "staff", "files"])
class ChecklistsModule
