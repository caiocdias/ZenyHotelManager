package com.zeny.hotel.checklists

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `checklists` (ARCHITECTURE §3.1, SPEC §5.15 e §8.2.11; camada Plataforma): o motor
 * genérico de checklists usado por governança, manutenção, áreas comuns, restaurante e eventos. Pode depender
 * de `shared`, `hotel`, `staff` e `files`; não conhece UHs, estoque, rouparia nem manutenção: tipos de UH,
 * tipos de limpeza, áreas, ativos, insumos e tipos de enxoval são IDs opacos (H3 do roadmap do M2).
 *
 * Usa `hotel` (o fuso e o evento `BusinessDateAdvanced`, que gera as rotinas, D1), `staff` (a escala do dia, que
 * dá os candidatos das rotinas, H14) e `files` (as fotos dos itens, finalidade `checklists.photo`), só pelas APIs
 * públicas.
 *
 * A raiz do pacote é a API pública: [ChecklistsApi] (escolher o modelo de um sujeito, ler uma versão publicada,
 * iniciar e concluir execuções), os ids, o conteúdo de um modelo ([ChecklistContent]), as execuções e rotinas
 * ([ChecklistExecutionView], [ChecklistRoutineView]), o evento [ChecklistCompleted], a sincronização offline
 * ([ChecklistsOffline]), a finalidade da foto ([ChecklistsFilePurposes]), os códigos de erro
 * ([ChecklistsErrorCodes]) e as permissões ([ChecklistsPermissions]).
 */
@PackageInfo
@ApplicationModule(displayName = "Checklists", allowedDependencies = ["shared", "hotel", "staff", "files"])
class ChecklistsModule
