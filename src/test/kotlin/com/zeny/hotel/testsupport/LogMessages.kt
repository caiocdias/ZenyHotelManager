package com.zeny.hotel.testsupport

private val CONSOLE_PREFIX = Regex("""^\S+\s+[A-Z]+\s+\d+ --- .*? : """)
private val CONSOLE_PREFIX_ONLY = Regex("""^\S+\s+[A-Z]+\s+\d+ --- """)

/**
 * Só a parte de mensagem de cada linha do console do Spring Boot (o texto depois do ` : ` do logger), sem data,
 * PID e thread. Asserções sobre números curtos (`100`, `1990`) não podem olhar o prefixo: o PID da JVM e os
 * milissegundos do carimbo variam por execução e podem conter o valor. Linhas sem prefixo (continuação, como
 * pilha de exceção) ficam inteiras.
 */
fun messagesOf(log: String): String =
    log
        .lineSequence()
        .map { line ->
            when {
                CONSOLE_PREFIX.containsMatchIn(line) -> line.replace(CONSOLE_PREFIX, "")
                CONSOLE_PREFIX_ONLY.containsMatchIn(line) -> ""
                else -> line
            }
        }.joinToString("\n")
