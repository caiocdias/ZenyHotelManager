package com.zeny.hotel.shared.infrastructure.persistence

import org.flywaydb.core.api.configuration.FluentConfiguration
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component

/**
 * Convenção de migrações (ARCHITECTURE §11): cada módulo tem uma pasta
 * `db/migration/<módulo>/` com arquivos `V<aaaa>_<mm>_<dd>_<hhmm>__<descricao>.sql`, e o nome
 * da pasta é o schema do módulo.
 *
 * Este customizador descobre as pastas no classpath e entrega a lista ao Flyway como
 * `schemas`: o Flyway cria os schemas que faltarem antes de migrar. Um módulo novo só precisa
 * criar a pasta. As versões são globais (um único histórico, `shared.flyway_schema_history`),
 * então a ordem de aplicação é a do timestamp, não a das pastas.
 */
@Component
class ModuleSchemasFlywayCustomizer : FlywayConfigurationCustomizer {
    @Suppress("SpreadOperator") // a API do Flyway é vararg; roda uma vez, no boot
    override fun customize(configuration: FluentConfiguration) {
        val resolver = PathMatchingResourcePatternResolver(configuration.classLoader)
        val schemas = moduleSchemas(resolver.getResources("classpath*:$ROOT/*/*.sql").map { it.url.toString() })
        configuration.schemas(*schemas.toTypedArray())
    }

    companion object {
        const val ROOT = "db/migration"
        const val SHARED_SCHEMA = "shared"
        private val SCHEMA_NAME = Regex("^[a-z][a-z0-9_]*$")

        /**
         * Schemas a partir das URLs das migrações: o nome da pasta logo abaixo de [ROOT].
         * `shared` vem primeiro; os demais em ordem alfabética.
         */
        fun moduleSchemas(migrationUrls: List<String>): List<String> {
            val folders =
                migrationUrls
                    .map { url -> url.substringAfterLast("$ROOT/").substringBefore('/') }
                    .toSortedSet()
            folders.forEach {
                check(SCHEMA_NAME.matches(it)) { "Pasta de migração '$it' não serve como nome de schema" }
            }
            check(SHARED_SCHEMA in folders) { "Pasta de migração '$ROOT/$SHARED_SCHEMA' não encontrada" }
            return listOf(SHARED_SCHEMA) + (folders - SHARED_SCHEMA)
        }
    }
}
