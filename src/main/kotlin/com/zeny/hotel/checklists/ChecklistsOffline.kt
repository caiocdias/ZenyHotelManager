package com.zeny.hotel.checklists

import com.zeny.hotel.shared.OfflineCommandType
import com.zeny.hotel.shared.OfflineSource

/**
 * O `checklists` na sincronização offline (ARCHITECTURE §9 e §6.2, RF-CHK-07, M2 E07).
 *
 * Fontes do download (ambas exigem [ChecklistsPermissions.EXECUTIONS_EXECUTE]):
 * - [TEMPLATES]: cada modelo ativo e publicado, com a última versão publicada (o conteúdo que o app executa:
 *   seções, itens, condições, ações e aplicabilidade). Desativado, ou nunca publicado, vai como *tombstone*. Os
 *   textos são os do modelo (rótulos e títulos escritos por quem o mantém), nunca dado de hóspede (RN-13).
 * - [EXECUTIONS]: as execuções pendentes e em andamento de que o usuário é candidato ou executor (com `OWN`; com
 *   `ALL`, todas), sem as respostas (nada de texto livre); concluída, vai como *tombstone*.
 *
 * Comandos do upload: [START] (`StartChecklistExecution`) e [COMPLETE] (`CompleteChecklistExecution`), com os
 * horários do dispositivo corrigidos pela defasagem (RN-22).
 */
object ChecklistsOffline {
    val TEMPLATES = OfflineSource("checklist-templates", ChecklistsPermissions.EXECUTIONS_EXECUTE)
    val EXECUTIONS = OfflineSource("checklist-executions", ChecklistsPermissions.EXECUTIONS_EXECUTE)

    /** Inicia uma execução (nova, com o `commandId` como id, ou a pendente de uma rotina). */
    val START = OfflineCommandType("checklists.start-execution")

    /** Conclui uma execução com as respostas, as fotos (pelo `clientFileId`) e os horários. */
    val COMPLETE = OfflineCommandType("checklists.complete-execution")
}
