package com.zeny.hotel.shared

import java.util.Collections
import java.util.IdentityHashMap

/**
 * A forma de logar uma falha sem vazar dados (ARCHITECTURE §13 e §16): a mensagem de uma exceção do banco ou da
 * assinatura pode trazer nomes de tabela, valores e trechos de chave, e o *stack trace* leva a mensagem das causas.
 * O log leva só as classes.
 */
object ExceptionClasses {
    /** O teto de elos da cadeia: uma cadeia mais longa que isso é cortada, sem erro. */
    const val MAX_LINKS = 10

    /**
     * `A <- B <- C`: o nome da classe da [failure] e o das causas, em ordem. Para na primeira causa repetida (ciclo)
     * e em [MAX_LINKS] elos. Nunca traz a mensagem, o *stack trace* nem as exceções suprimidas.
     */
    fun chain(failure: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        return generateSequence(failure) { it.cause }
            .takeWhile { seen.add(it) }
            .take(MAX_LINKS)
            .joinToString(" <- ") { it.javaClass.name }
    }
}
