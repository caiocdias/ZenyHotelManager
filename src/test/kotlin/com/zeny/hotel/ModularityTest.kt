package com.zeny.hotel

import com.zeny.hotel.billing.BillingModule
import com.zeny.hotel.checklists.ChecklistsModule
import com.zeny.hotel.guests.GuestsModule
import com.zeny.hotel.notifications.NotificationsModule
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifica os limites entre módulos (ARCHITECTURE §3, §4 e §17): grafo acíclico e
 * acesso só à API pública (raiz do pacote) de outro módulo. Também gera a documentação
 * dos módulos em `build/spring-modulith-docs`.
 */
class ModularityTest {
    private val modules = ApplicationModules.of(ZenyHotelApplication::class.java)

    @Test
    fun `module structure is valid`() {
        modules.verify()
    }

    @Test
    fun `shared is declared as a shared module`() {
        val shared = modules.getModuleByName("shared")

        shared.isPresent shouldBe true
        shared.get().displayName shouldBe "Shared kernel"
        modules.sharedModules.map { it.identifier.toString() } shouldContain "shared"
    }

    @Test
    fun `iam depends only on shared`() {
        val iam = modules.getModuleByName("iam").orElseThrow()

        iam
            .getDirectDependencies(modules)
            .uniqueModules()
            .map { it.identifier.toString() }
            .toList() shouldBe
            listOf("shared")
    }

    @Test
    fun `hotel depends only on shared and files, and only on the public API of files`() {
        val hotel = modules.getModuleByName("hotel").orElseThrow()

        hotel
            .getDirectDependencies(modules)
            .uniqueModules()
            .map { it.identifier.toString() }
            .toList() shouldContainExactlyInAnyOrder
            listOf("shared", "files")
        hotel
            .getDirectDependencies(modules)
            .stream()
            .map { it.targetType.name }
            .filter { it.startsWith("com.zeny.hotel.files.") }
            .filter { it.substringBeforeLast('.') != "com.zeny.hotel.files" }
            .toList() shouldBe emptyList()
    }

    @Test
    fun `files depends only on shared`() {
        val files = modules.getModuleByName("files").orElseThrow()

        files
            .getDirectDependencies(modules)
            .uniqueModules()
            .map { it.identifier.toString() }
            .toList() shouldBe
            listOf("shared")
    }

    @Test
    fun `staff depends only on shared, iam and hotel, and only on their public APIs`() {
        val staff = modules.getModuleByName("staff").orElseThrow()

        staff
            .getDirectDependencies(modules)
            .uniqueModules()
            .map { it.identifier.toString() }
            .toList() shouldContainExactlyInAnyOrder
            listOf("shared", "iam", "hotel")
        // Só a raiz de iam e hotel (a API pública); verify() barra os pacotes internos.
        val internalTypes =
            staff
                .getDirectDependencies(modules)
                .stream()
                .map { it.targetType.name }
                .filter { it.startsWith("com.zeny.hotel.iam.") || it.startsWith("com.zeny.hotel.hotel.") }
                .filter { it.substringBeforeLast('.').count { char -> char == '.' } > 3 }
                .toList()
        internalTypes shouldBe emptyList()
    }

    @Test
    fun `rooms depends only on shared and hotel, and only on their public APIs`() {
        val rooms = modules.getModuleByName("rooms").orElseThrow()
        val dependencies =
            rooms
                .getDirectDependencies(modules)
                .uniqueModules()
                .map { it.identifier.toString() }
                .toList()

        dependencies shouldContain "shared"
        (dependencies - setOf("shared", "hotel")) shouldBe emptyList()
        // Só a raiz de hotel (a API pública); verify() barra os pacotes internos.
        rooms
            .getDirectDependencies(modules)
            .stream()
            .map { it.targetType.name }
            .filter { it.startsWith("com.zeny.hotel.hotel.") }
            .filter { it.substringBeforeLast('.') != "com.zeny.hotel.hotel" }
            .toList() shouldBe emptyList()
    }

    @Test
    fun `checklists depends only on shared, hotel, staff and files, and only on their public APIs`() {
        val checklists = modules.getModuleByName("checklists").orElseThrow()
        val allowed = setOf("shared", "hotel", "staff", "files")
        val dependencies =
            checklists
                .getDirectDependencies(modules)
                .uniqueModules()
                .map { it.identifier.toString() }
                .toList()

        // ARCHITECTURE §3.1: shared, hotel (data operacional e BusinessDateAdvanced), staff (escala do dia) e files
        // (fotos), exatamente (M2 E07).
        dependencies shouldContainExactlyInAnyOrder listOf("shared", "hotel", "staff", "files")
        (dependencies - allowed) shouldBe emptyList()
        // O módulo declara exatamente a lista da ARCHITECTURE: rooms, inventory e afins ficam barrados pelo verify().
        ChecklistsModule::class.java
            .getAnnotation(ApplicationModule::class.java)
            .allowedDependencies
            .toSet() shouldBe allowed
        // Nenhum tipo interno de outro módulo (só a raiz de cada um; verify() também barra).
        checklists
            .getDirectDependencies(modules)
            .stream()
            .map { it.targetType.name }
            .filter { it.startsWith("com.zeny.hotel.") && !it.startsWith("com.zeny.hotel.checklists.") }
            .filter { !it.startsWith("com.zeny.hotel.shared.") }
            .filter { it.substringBeforeLast('.').count { char -> char == '.' } > 3 }
            .toList() shouldBe emptyList()
    }

    @Test
    fun `notifications depends only on shared and iam, and only on the public API of iam`() {
        val notifications = modules.getModuleByName("notifications").orElseThrow()
        val allowed = setOf("shared", "iam")

        // ARCHITECTURE §3.1 e H9 do M2: iam pela IamApi (destinatários por permissão e sessões ativas).
        notifications
            .getDirectDependencies(modules)
            .uniqueModules()
            .map { it.identifier.toString() }
            .toList() shouldContainExactlyInAnyOrder
            listOf("shared", "iam")
        NotificationsModule::class.java
            .getAnnotation(ApplicationModule::class.java)
            .allowedDependencies
            .toSet() shouldBe allowed
        // Só a raiz de iam (a API pública); verify() também barra os pacotes internos.
        notifications
            .getDirectDependencies(modules)
            .stream()
            .map { it.targetType.name }
            .filter { it.startsWith("com.zeny.hotel.iam.") }
            .filter { it.substringBeforeLast('.') != "com.zeny.hotel.iam" }
            .toList() shouldBe emptyList()
    }

    @Test
    fun `guests depends only on shared and files`() {
        val guests = modules.getModuleByName("guests").orElseThrow()

        // ARCHITECTURE §3.1 (camada Cadastros): files com os anexos do hóspede (E03 do M3), só pela FilesApi.
        guests
            .getDirectDependencies(modules)
            .uniqueModules()
            .map { it.identifier.toString() }
            .toList() shouldContainExactlyInAnyOrder
            listOf("shared", "files")
        GuestsModule::class.java
            .getAnnotation(ApplicationModule::class.java)
            .allowedDependencies
            .toSet() shouldBe setOf("shared", "files")
    }

    @Test
    fun `billing depends only on shared, hotel, guests and notifications, and only on their public APIs`() {
        val billing = modules.getModuleByName("billing").orElseThrow()
        val allowed = setOf("shared", "hotel", "guests", "notifications")

        // ARCHITECTURE §3.1 (camada Financeiro): hotel (data operacional) e guests (empresa dona ou pagadora), M3 E04;
        // notifications (a diferença no fechamento do turno de caixa, H14), M3 E07; payments entra na E08. Nunca
        // iam, reservas nem fiscal.
        billing
            .getDirectDependencies(modules)
            .uniqueModules()
            .map { it.identifier.toString() }
            .toList() shouldContainExactlyInAnyOrder
            allowed.toList()
        BillingModule::class.java
            .getAnnotation(ApplicationModule::class.java)
            .allowedDependencies
            .toSet() shouldBe allowed
        // Só a raiz de hotel, guests e notifications (a API pública); verify() também barra os pacotes internos.
        val publicApis = setOf("com.zeny.hotel.hotel", "com.zeny.hotel.guests", "com.zeny.hotel.notifications")
        billing
            .getDirectDependencies(modules)
            .stream()
            .map { it.targetType.name }
            .filter { name -> publicApis.any { name.startsWith("$it.") } }
            .filter { it.substringBeforeLast('.') !in publicApis }
            .toList() shouldBe emptyList()
    }

    @Test
    fun `no module other than staff and notifications depends on iam, only on the ports of shared`() {
        val dependents =
            modules
                .filter { it.identifier.toString() != "iam" }
                .filter { module ->
                    module.getDirectDependencies(modules).uniqueModules().anyMatch { it.identifier.toString() == "iam" }
                }.map { it.identifier.toString() }

        (dependents - setOf("staff", "notifications")) shouldBe emptyList()
    }

    @Test
    fun `writes module documentation`() {
        Documenter(modules).writeDocumentation()

        Files.exists(Path.of("build", "spring-modulith-docs", "components.puml")) shouldBe true
    }
}
