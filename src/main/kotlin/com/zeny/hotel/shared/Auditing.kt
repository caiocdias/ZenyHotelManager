package com.zeny.hotel.shared

import java.time.Instant
import java.util.UUID

/**
 * Marca um caso de uso de escrita para a trilha de auditoria (RF-ACS-11, RNF-11, ARCHITECTURE §5):
 * o aspecto do `shared` grava as entradas **na mesma transação** do método, então rollback não
 * deixa entrada e falha ao gravar a entrada desfaz a escrita. O método precisa rodar numa
 * transação de escrita (`@Transactional` sem `readOnly`); sem ela, falha com
 * `IllegalStateException` antes de executar.
 *
 * Dentro do método, o caso de uso descreve o que mudou pela [AuditContext]. Sem nenhuma descrição,
 * o aspecto grava uma entrada só com a ação (nunca deixa a escrita sem entrada); entidade
 * acompanhada que não mudou não gera entrada, e [AuditContext.noChanges] declara que nada mudou.
 *
 * - [action]: código estável da ação, `<módulo>.<entidade>.<verbo>` em minúsculas e kebab-case
 *   (ex.: `iam.user.create`); um código publicado não muda de sentido.
 * - [recordRejections]: grava também a recusa por [DomainException] (resultado
 *   [AuditOutcome.REJECTED] com o `code`), com as entidades já acompanhadas. A entrada só fica se a
 *   transação confirmar (`noRollbackFor`), como a falha de login que conta para o bloqueio.
 *
 * Todo método `@Transactional` de escrita da camada `application` leva esta anotação (regra
 * ArchUnit `writeUseCasesAreAudited`).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Audited(
    val action: String,
    val recordRejections: Boolean = false,
)

/**
 * Propriedade de um retrato de auditoria cujo valor nunca vai para a trilha: sai como `"***"`
 * (por exemplo, o valor decifrado de um campo sensível). Senha, PIN, token, hash, segredo e
 * valores cifrados já são mascarados pelo nome da propriedade e pelo formato do valor.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class AuditMasked

/**
 * Porta do caso de uso dentro de um método [Audited] (implementada no `shared`): descreve as
 * entidades que a escrita muda. Os retratos (`snapshot`) são objetos de valor (data classes,
 * mapas, listas) com só o que interessa à trilha, sem hash de senha ou PIN, token nem valor
 * decifrado; o que escapar é mascarado pela trilha. Fora de um método [Audited], as chamadas
 * falham com `IllegalStateException`.
 */
interface AuditContext {
    /**
     * Acompanha uma entidade existente: o retrato de agora é o "antes" e o do fim do método, o
     * "depois". Sem mudança entre os dois, não há entrada.
     */
    fun track(
        entityType: String,
        entityId: Any,
        snapshot: () -> Any?,
    )

    /** Uma entidade criada pelo método: sem "antes"; o "depois" é o retrato do fim do método. */
    fun created(
        entityType: String,
        entityId: Any,
        snapshot: () -> Any?,
    )

    /** Uma mudança com "antes" e "depois" já conhecidos. */
    fun record(
        entityType: String,
        entityId: Any,
        before: Any?,
        after: Any?,
    )

    /**
     * Atribui as entradas a um usuário e sessão quando a requisição não traz o ator (login,
     * refresh): o usuário autenticado pelo próprio caso de uso.
     */
    fun attributeTo(
        userId: UserId,
        deviceSessionId: DeviceSessionId?,
    )

    /** A aprovação de superior (step-up, M1 E05) que autorizou a ação. */
    fun approval(approvalId: UUID)

    /**
     * Declara que esta execução não escreveu nada (por exemplo, o administrador inicial que já
     * existe): sem outras descrições, nenhuma entrada é gravada.
     */
    fun noChanges()
}

/** Resultado da ação auditada. */
enum class AuditOutcome {
    SUCCEEDED,
    REJECTED,
}

/**
 * Uma entrada pronta para a trilha. [userId] nulo é o sistema (boot, jobs). [before] e [after]
 * são os retratos ainda sem máscara: a trilha os serializa e mascara; por isso não aparecem em
 * `toString`.
 */
@Suppress("LongParameterList") // os campos da entrada (RF-ACS-11)
class AuditRecord(
    val occurredAt: Instant,
    val action: String,
    val outcome: AuditOutcome,
    val errorCode: String?,
    val userId: UserId?,
    val deviceSessionId: DeviceSessionId?,
    val entityType: String?,
    val entityId: String?,
    val before: Any?,
    val after: Any?,
    val approvalId: UUID?,
) {
    override fun toString(): String = "AuditRecord(action=$action, outcome=$outcome, entityType=$entityType)"
}

/**
 * Porta de gravação da trilha (implementada por `audit`): grava na transação corrente, nunca
 * numa própria, e mascara os campos sensíveis dos retratos.
 */
fun interface AuditTrail {
    fun append(records: List<AuditRecord>)
}

/**
 * Tabelas *append-only* de um módulo (H4, RN-03): o papel da aplicação só lê e insere nelas. A
 * partida da aplicação confere, para cada tabela declarada por um bean deste tipo, que o papel
 * conectado não consegue `UPDATE`, `DELETE` nem `TRUNCATE`.
 */
fun interface AppendOnlyTables {
    /** Nomes qualificados com o schema (`audit.audit_entry`). */
    fun tables(): Set<String>
}
