package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhIntegrationLog
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import org.slf4j.Logger
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.format.DateTimeFormatter

// Os cuidados do adaptador do Serpro com o que pode falhar em volta da chamada: nenhum deles troca o resultado do
// Serpro nem deixa mensagem ou causa irem ao log (só a classe da exceção).

/** O status e o corpo (limitado) da resposta. */
internal class Reply(
    val status: Int,
    val body: ByteArray,
)

/** O envio que terminou ([Done]) ou falhou ([Failed]) com uma exceção inesperada. */
internal sealed interface Sent {
    class Done(
        val reply: Reply,
    ) : Sent

    class Failed(
        val error: RuntimeException,
    ) : Sent
}

private val BR_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** Um registro que falha não pode trocar o resultado do Serpro (a E08 repetiria um POST): só a classe vai ao log. */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun recordSafely(
    log: Logger,
    integrationLog: FnrhIntegrationLog,
    operation: String,
    call: FnrhCall,
) {
    try {
        integrationLog.record(call)
    } catch (e: RuntimeException) {
        log.warn("FNRH {}: o registro da chamada falhou ({})", operation, e.javaClass.simpleName)
    }
}

/** A URL da chamada, ou `null` se não der para montá-la (nada saiu). Só a classe da falha vai ao log. */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun uriOrNull(
    log: Logger,
    operation: String,
    base: URI,
    segments: List<String>,
    query: List<Pair<String, String>>,
): URI? =
    try {
        requestUri(base, segments, query)
    } catch (e: RuntimeException) {
        log.warn("FNRH {}: montagem da requisição falhou ({})", operation, e.javaClass.simpleName)
        null
    }

/** Executa o envio; só a classe da falha vai ao log, nunca a mensagem nem a causa (podem trazer o corpo). */
@Suppress("TooGenericExceptionCaught")
internal inline fun trySend(
    log: Logger,
    operation: String,
    send: () -> Reply,
): Sent =
    try {
        Sent.Done(send())
    } catch (e: RuntimeException) {
        log.warn("FNRH {}: falha no envio ({})", operation, e.javaClass.simpleName)
        Sent.Failed(e)
    }

/**
 * O item é o da reserva pedida: sem `numero_reserva` (ausente ou JSON `null`) vale; com ele, só se for texto igual ao
 * código ("RES-1" não é "RES-10"). Um `numero_reserva` presente que não é texto (número, objeto) nunca vale.
 */
internal fun matchesCode(
    item: JsonNode,
    reservationCode: String,
): Boolean {
    val code = item.get("numero_reserva")
    return code == null || code.isNull || (code.isString && code.asString() == reservationCode)
}

/** Os dados da pessoa que o Serpro pode ecoar numa mensagem de erro, além de nome, documento, e-mail e telefone. */
internal fun personSensitiveValues(person: FnrhPersonData): List<String> =
    listOfNotNull(
        person.gender,
        person.genderDescription,
        person.race,
        person.disabilityOption,
        person.disabilityType,
        person.birthDate?.toString(),
        person.birthDate?.format(BR_DATE),
        person.postalCode,
        person.postalCode?.filter(Char::isDigit),
        person.street,
        person.number,
        person.complement,
        person.neighborhood,
    ) + postalCodeParts(person.postalCode)

/** "01310-100" pode voltar só como "100": cada parte do CEP também é um valor enviado. */
private fun postalCodeParts(postalCode: String?): List<String> = postalCode?.split('-').orEmpty()
