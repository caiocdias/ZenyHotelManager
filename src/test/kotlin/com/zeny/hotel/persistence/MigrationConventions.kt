package com.zeny.hotel.persistence

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Regras da convenção de migrações (ARCHITECTURE §11): uma pasta por módulo, cujo nome é o
 * schema do módulo, e arquivos `V<aaaa>_<mm>_<dd>_<hhmm>__<descricao>.sql`. Cada função
 * devolve a lista de violações (vazia quando está tudo certo), para que os testes possam
 * provar que cada regra pega o erro que deve pegar.
 */
object MigrationConventions {
    private val FILE_NAME = Regex("""^V(\d{4}_\d{2}_\d{2}_\d{4})__[a-z0-9]+(_[a-z0-9]+)*\.sql$""")
    private val TIMESTAMP = DateTimeFormatter.ofPattern("uuuu_MM_dd_HHmm").withResolverStyle(ResolverStyle.STRICT)
    private val SCHEMA_NAME = Regex("^[a-z][a-z0-9_]*$")

    private const val NAME = """[\w."]+"""

    /**
     * Tabelas, views, sequences, funções e tipos criados, alterados ou removidos. Captura a
     * lista inteira de nomes (`DROP TABLE a, b`).
     */
    private val OBJECT_DDL =
        Regex(
            """\b(?:create(?:\s+or\s+replace)?|alter|drop)\s+""" +
                """(?:unlogged\s+)?(?:materialized\s+view|table|view|sequence|function|procedure|type|domain)\s+""" +
                """(?:if\s+(?:not\s+)?exists\s+)?(?:only\s+)?($NAME(?:\s*,\s*$NAME)*)""",
            RegexOption.IGNORE_CASE,
        )

    /** Tabela de destino de FK (`REFERENCES`), de trigger (`CREATE TRIGGER … ON`) e de `INSERT INTO`. */
    private val TABLE_REFERENCES =
        listOf(
            Regex("""\breferences\s+($NAME)""", RegexOption.IGNORE_CASE),
            Regex(
                """\bcreate\s+(?:or\s+replace\s+)?(?:constraint\s+)?trigger\s+[\w"]+[^;]*?\bon\s+($NAME)""",
                RegexOption.IGNORE_CASE,
            ),
            Regex("""\binsert\s+into\s+($NAME)""", RegexOption.IGNORE_CASE),
        )

    /**
     * `GRANT`/`REVOKE … ON <lista> TO|FROM`. O grupo 1 marca as formas que nomeiam o schema
     * (`ON SCHEMA s`, `ON ALL TABLES IN SCHEMA s`); o grupo 2 é a lista de objetos.
     */
    private val PRIVILEGES =
        Regex(
            """\b(?:grant|revoke)\b[^;]*?\bon\s+""" +
                """(?:(schema|all\s+(?:tables|sequences|functions|procedures|routines)\s+in\s+schema)\s+|""" +
                """(?:table|sequence|function|procedure|routine)\s+)?([^;]+?)\s+(?:to|from)\b""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * `ALTER DEFAULT PRIVILEGES [FOR ROLE …] [IN SCHEMA …] GRANT|REVOKE … ON TABLES|SEQUENCES|…`:
     * vale para os objetos criados depois, e só pode nomear o schema do próprio módulo. Sem
     * `IN SCHEMA`, valeria para todo schema; por isso é recusado.
     */
    private val DEFAULT_PRIVILEGES = Regex("""\balter\s+default\s+privileges\b([^;]*)""", RegexOption.IGNORE_CASE)
    private val GRANT_OR_REVOKE = Regex("""\b(?:grant|revoke)\b""", RegexOption.IGNORE_CASE)
    private val IN_SCHEMA = Regex("""\bin\s+schema\s+($NAME(?:\s*,\s*$NAME)*)""", RegexOption.IGNORE_CASE)

    /** Índices: o nome não leva schema; o que importa é a tabela depois do `ON`. */
    private val INDEX_DDL =
        Regex(
            """\bcreate\s+(?:unique\s+)?index\s+(?:concurrently\s+)?(?:if\s+not\s+exists\s+)?""" +
                """[\w"]*\s*on\s+(?:only\s+)?([\w."]+)""",
            RegexOption.IGNORE_CASE,
        )

    private val CREATE_SCHEMA = Regex("""\bcreate\s+schema\b""", RegexOption.IGNORE_CASE)
    private val LINE_COMMENT = Regex("--[^\n]*")
    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    /** Versão Flyway do arquivo (`2026.10.13.1200`), ou `null` se o nome foge da convenção. */
    fun versionOf(fileName: String): String? =
        FILE_NAME
            .matchEntire(fileName)
            ?.groupValues
            ?.get(1)
            ?.replace('_', '.')

    fun checkFileName(fileName: String): List<String> {
        val match =
            FILE_NAME.matchEntire(fileName)
                ?: return listOf("$fileName: nome fora do padrão V<aaaa>_<mm>_<dd>_<hhmm>__<descricao>.sql")
        return try {
            LocalDateTime.parse(match.groupValues[1], TIMESTAMP)
            emptyList()
        } catch (_: DateTimeParseException) {
            listOf("$fileName: a versão não é uma data e hora válidas")
        }
    }

    fun checkFolderName(
        folder: String,
        modules: Set<String>,
    ): List<String> =
        buildList {
            if (!SCHEMA_NAME.matches(folder)) add("$folder: nome de pasta não serve como nome de schema")
            if (folder !in modules) add("$folder: não há módulo com esse nome (módulos: ${modules.sorted()})")
        }

    /**
     * O SQL só pode criar, mudar, referenciar (FK, trigger), popular ou dar permissão sobre
     * objetos qualificados com o schema do próprio módulo. São expressões regulares, não um
     * parser: cobrem as formas usuais, não toda a gramática do PostgreSQL.
     */
    fun checkStatements(
        schema: String,
        fileName: String,
        sql: String,
    ): List<String> {
        val withDefaults = sql.replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, " ")
        val defaultPrivileges = DEFAULT_PRIVILEGES.findAll(withDefaults).map { it.groupValues[1] }.toList()
        // O GRANT/REVOKE de dentro do ALTER DEFAULT PRIVILEGES é conferido à parte (ON TABLES não nomeia objeto).
        val code = withDefaults.replace(DEFAULT_PRIVILEGES, " ")
        val objects =
            (OBJECT_DDL.findAll(code) + INDEX_DDL.findAll(code)).flatMap { it.groupValues[1].split(',') } +
                TABLE_REFERENCES.asSequence().flatMap { rule -> rule.findAll(code).map { it.groupValues[1] } }
        val privileges = PRIVILEGES.findAll(code).toList()
        val grantedObjects =
            privileges
                .filter { it.groupValues[1].isEmpty() }
                .flatMap { withoutParentheses(it.groupValues[2]).split(',') }
        val grantedSchemas = privileges.filter { it.groupValues[1].isNotEmpty() }.map { it.groupValues[2] }
        return buildList {
            (objects + grantedObjects)
                .map { it.trim() }
                .filterNot { it.replace("\"", "").startsWith("$schema.") }
                .forEach { add("$fileName: '$it' deve ser qualificado com o schema '$schema'") }
            grantedSchemas
                .map { it.trim().replace("\"", "") }
                .filterNot { it == schema }
                .forEach { add("$fileName: permissão sobre o schema '$it'; só vale o schema '$schema'") }
            defaultPrivileges.forEach { clause ->
                val target = GRANT_OR_REVOKE.split(clause, limit = 2).first()
                val schemas =
                    IN_SCHEMA
                        .find(target)
                        ?.groupValues
                        ?.get(1)
                        ?.split(',')
                        .orEmpty()
                if (schemas.isEmpty()) {
                    add(
                        "$fileName: ALTER DEFAULT PRIVILEGES sem IN SCHEMA vale para todos os schemas; " +
                            "use IN SCHEMA $schema",
                    )
                }
                schemas
                    .map { it.trim().replace("\"", "") }
                    .filterNot { it == schema }
                    .forEach { add("$fileName: privilégios padrão no schema '$it'; só vale o schema '$schema'") }
            }
            if (CREATE_SCHEMA.containsMatchIn(code)) {
                add("$fileName: não crie schema na migração; o Flyway cria o schema a partir da pasta")
            }
        }
    }

    /**
     * O texto sem o que está entre parênteses, inclusive aninhados: a assinatura de uma função
     * (`f(numeric(14,2), text)` → `f`), cujas vírgulas não separam objetos.
     */
    private fun withoutParentheses(text: String): String {
        var depth = 0
        return buildString {
            text.forEach { char ->
                when {
                    char == '(' -> depth++
                    char == ')' -> depth = maxOf(0, depth - 1)
                    depth == 0 -> append(char)
                }
            }
        }
    }

    /** Versões repetidas entre todas as pastas (o Flyway recusaria subir). */
    fun checkUniqueVersions(fileNames: List<String>): List<String> =
        fileNames
            .groupBy { versionOf(it) }
            .filterKeys { it != null }
            .filterValues { it.size > 1 }
            .map { (version, files) -> "versão $version repetida em ${files.sorted()}" }
}
