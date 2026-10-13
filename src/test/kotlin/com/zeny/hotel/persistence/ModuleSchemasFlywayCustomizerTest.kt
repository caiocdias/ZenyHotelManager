package com.zeny.hotel.persistence

import com.zeny.hotel.shared.infrastructure.persistence.ModuleSchemasFlywayCustomizer.Companion.moduleSchemas
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Descoberta dos schemas a partir das pastas de migração, sem banco. */
class ModuleSchemasFlywayCustomizerTest {
    private fun url(path: String) = "jar:nested:/app/app.jar/!BOOT-INF/classes/!/db/migration/$path"

    @Test
    fun `one schema per folder with shared first`() {
        val urls =
            listOf(
                url("rooms/V2026_10_14_0900__create_room.sql"),
                url("shared/V2026_10_13_1200__enable_btree_gist.sql"),
                url("iam/V2026_10_14_0800__create_user.sql"),
                url("rooms/V2026_10_14_0901__create_room_type.sql"),
            )

        moduleSchemas(urls) shouldBe listOf("shared", "iam", "rooms")
    }

    @Test
    fun `rejects folders that cannot be schema names`() {
        shouldThrow<IllegalStateException> {
            moduleSchemas(listOf(url("shared/V1__x.sql"), url("Front-Desk/V2__x.sql")))
        }
    }

    @Test
    fun `requires the shared folder`() {
        shouldThrow<IllegalStateException> { moduleSchemas(listOf(url("rooms/V1__x.sql"))) }
    }
}
