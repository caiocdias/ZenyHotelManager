package com.zeny.hotel.sync

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.OfflineChange
import com.zeny.hotel.shared.OfflineChangeRequest
import com.zeny.hotel.shared.OfflineChangeSource
import com.zeny.hotel.shared.OfflineCommand
import com.zeny.hotel.shared.OfflineCommandHandler
import com.zeny.hotel.shared.OfflineCommandOutcome
import com.zeny.hotel.shared.OfflineCommandType
import com.zeny.hotel.shared.OfflinePosition
import com.zeny.hotel.shared.OfflineSource
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.application.sync.OfflineChangesService
import com.zeny.hotel.shared.application.sync.OfflineCommandService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeSortedWith
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** As conferências de boot do protocolo offline: fontes e tipos de comando únicos, permissões declaradas. */
class OfflineSyncCatalogTest {
    private val read = PermissionCode("catalogtest.items.read")
    private val permissions =
        listOf<ModulePermissions>(
            object : ModulePermissions {
                override val module = "catalogtest"
                override val permissions = listOf(PermissionDefinition(read, "Ler", setOf(ALL)))
            },
        )

    @Test
    fun `sources need unique names and a declared permission`() {
        OfflineChangesService
            .catalog(
                listOf(source("b", read), source("a", read)),
                permissions,
            ).map { it.source.name } shouldBe
            listOf("a", "b")
        shouldThrow<IllegalArgumentException> {
            OfflineChangesService.catalog(listOf(source("a", read), source("a", read)), permissions)
        }
        shouldThrow<IllegalArgumentException> {
            OfflineChangesService.catalog(listOf(source("a", PermissionCode("catalogtest.items.write"))), permissions)
        }
    }

    @Test
    fun `command types are unique and well formed`() {
        OfflineCommandService.catalog(listOf(handler("catalogtest.do-it"), handler("catalogtest.undo"))).keys shouldBe
            setOf("catalogtest.do-it", "catalogtest.undo")
        shouldThrow<IllegalArgumentException> {
            OfflineCommandService.catalog(listOf(handler("catalogtest.do-it"), handler("catalogtest.do-it")))
        }
        listOf("semmodulo", "Catalog.do", "catalog.do_it", "catalog.-x", "").forEach {
            shouldThrow<IllegalArgumentException> { OfflineCommandType(it) }
        }
        listOf("Rooms", "1rooms", "rooms_x", "").forEach {
            shouldThrow<IllegalArgumentException> { OfflineSource(it, read) }
        }
    }

    @Test
    fun `positions are ordered by transaction id and then by sequence`() {
        listOf(OfflinePosition(2, 1), OfflinePosition(1, 9), OfflinePosition(1, 3)).sorted() shouldBeSortedWith
            compareBy<OfflinePosition>({ it.xid }, { it.seq })
        (OfflinePosition(1, 9) < OfflinePosition(2, 1)) shouldBe true
    }

    private fun source(
        name: String,
        permission: PermissionCode,
    ) = object : OfflineChangeSource {
        override val source = OfflineSource(name, permission)

        override fun changes(request: OfflineChangeRequest): List<OfflineChange> = emptyList()
    }

    private fun handler(type: String) =
        object : OfflineCommandHandler<String> {
            override val type = OfflineCommandType(type)
            override val payloadType = String::class.java

            override fun handle(command: OfflineCommand<String>) = OfflineCommandOutcome.APPLIED
        }
}
