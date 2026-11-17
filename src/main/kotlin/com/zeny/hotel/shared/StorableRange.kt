package com.zeny.hotel.shared

import java.time.Instant
import java.time.LocalDate

/**
 * O alcance de datas e instantes que o PostgreSQL e o driver guardam (ARCHITECTURE §11), com os extremos inclusivos.
 *
 * O `date` e o `timestamptz` começam em `-4713-11-24`, mas o pgjdbc envia como `-infinity`, sem erro, qualquer data
 * ou instante anterior a `-4712-01-01`: esse é o piso efetivo. No topo, o `date` vai até `+5874897-12-31` e o
 * `timestamptz` até o último microssegundo de `+294276-12-31`. Um valor de fora que chega a uma consulta sem passar
 * por regra de negócio (como a chave do cursor, em [PageCursor]) é recusado antes do banco: senão a consulta falha
 * com 500 ou, abaixo do piso, devolve uma página errada em silêncio.
 */
object StorableRange {
    val MIN_DATE: LocalDate = LocalDate.of(-4712, 1, 1)
    val MAX_DATE: LocalDate = LocalDate.of(5_874_897, 12, 31)
    val MIN_INSTANT: Instant = Instant.parse("-4712-01-01T00:00:00Z")
    val MAX_INSTANT: Instant = Instant.parse("+294276-12-31T23:59:59.999999Z")

    fun contains(date: LocalDate): Boolean = date in MIN_DATE..MAX_DATE

    fun contains(instant: Instant): Boolean = instant in MIN_INSTANT..MAX_INSTANT
}
