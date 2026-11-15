package com.zeny.hotel.testsupport

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private val CONSOLE_PREFIX = Regex("""^\S+\s+[A-Z]+\s+\d+ --- .*? : """)
private val CONSOLE_PREFIX_ONLY = Regex("""^\S+\s+[A-Z]+\s+\d+ --- (\[[^\]]*\]\s*){0,3}""")
private val LOG_MAPPER = JsonMapper.builder().build()

/** Campos de ruído da linha ECS: variam por execução e não carregam conteúdo da mensagem. */
private val ECS_NOISE_TOP_LEVEL = setOf("@timestamp", "process", "ecs", "service")

/**
 * Só a parte de mensagem de cada linha de log, sem data, PID, thread e nível. Asserções sobre números curtos
 * (`100`, `1990`) não podem olhar o ruído: o PID da JVM e os milissegundos do carimbo variam por execução e podem
 * conter o valor.
 *
 * - Console do Spring Boot: fica o texto depois do ` : ` do logger; sem o separador, o texto depois do prefixo.
 * - Linha ECS JSON do logback: saem só `@timestamp`, `process`, `ecs`, `service` e `log.level`; todos os demais
 *   valores (`message`, `log.logger`, `error.*`, MDC e pares chave-valor) ficam, para que um dado pessoal em
 *   qualquer um deles continue visível às asserções negativas.
 * - Linhas sem prefixo (continuação, como pilha de exceção) e JSON inválido ficam inteiras.
 */
fun messagesOf(log: String): String =
    log
        .lineSequence()
        .map { line ->
            ecsValues(line)
                ?: when {
                    CONSOLE_PREFIX.containsMatchIn(line) -> line.replace(CONSOLE_PREFIX, "")
                    CONSOLE_PREFIX_ONLY.containsMatchIn(line) -> line.replace(CONSOLE_PREFIX_ONLY, "")
                    else -> line
                }
        }.joinToString("\n")

private fun ecsValues(line: String): String? {
    val root = if (line.trimStart().startsWith("{")) runCatching { LOG_MAPPER.readTree(line) }.getOrNull() else null
    if (root == null || !root.isObject) return null
    val values = mutableListOf<String>()
    root.properties().forEach { (name, node) ->
        when {
            name in ECS_NOISE_TOP_LEVEL -> Unit
            name == "log" && node.isObject -> collectLog(node, values)
            else -> collect(node, values)
        }
    }
    return values.joinToString(" ")
}

private fun collectLog(
    log: JsonNode,
    out: MutableList<String>,
) {
    log.properties().filter { (k, _) -> k != "level" }.forEach { (_, v) -> collect(v, out) }
}

private fun collect(
    node: JsonNode,
    out: MutableList<String>,
) {
    when {
        node.isObject -> node.properties().forEach { (_, v) -> collect(v, out) }
        node.isArray -> node.forEach { collect(it, out) }
        node.isNull -> Unit
        else -> out += node.asString()
    }
}
