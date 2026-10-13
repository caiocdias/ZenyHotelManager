package com.zeny.hotel.persistence

import com.zeny.hotel.ZenyHotelApplication
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Confere, sem banco, a convenção de migrações (ARCHITECTURE §11; passo a passo no CLAUDE.md):
 * `src/main/resources/db/migration/<módulo>/V<aaaa>_<mm>_<dd>_<hhmm>__<descricao>.sql`, pasta
 * com o nome de um módulo do Modulith, versões únicas entre todas as pastas e DDL sempre
 * qualificado com o schema do próprio módulo. Os testes negativos provam que cada regra pega
 * o erro correspondente.
 */
class MigrationConventionTest {
    private val migrationRoot = Path.of("src", "main", "resources", "db", "migration")
    private val testMigrationRoot = Path.of("src", "test", "resources", "db", "testmigration")

    private val moduleNames =
        ApplicationModules
            .of(ZenyHotelApplication::class.java)
            .map { it.identifier.toString() }
            .toSet()

    private fun moduleFolders(): List<Path> = migrationRoot.listDirectoryEntries().filter { it.isDirectory() }

    private fun migrations(folder: Path): List<Path> = folder.listDirectoryEntries().filter { it.isRegularFile() }

    @Test
    fun `every migration lives in a module folder`() {
        migrationRoot
            .listDirectoryEntries()
            .filter { it.isRegularFile() }
            .map { it.name }
            .shouldBeEmpty()
        moduleFolders().map { it.name } shouldContain "shared"
        moduleFolders()
            .flatMap { folder -> folder.listDirectoryEntries().filter { it.isDirectory() } }
            .map { it.toString() }
            .shouldBeEmpty()
    }

    @Test
    fun `folders are named after modules`() {
        moduleFolders().flatMap { MigrationConventions.checkFolderName(it.name, moduleNames) }.shouldBeEmpty()
    }

    @Test
    fun `file names follow the timestamp convention`() {
        moduleFolders().flatMap(::migrations).flatMap { MigrationConventions.checkFileName(it.name) }.shouldBeEmpty()
    }

    @Test
    fun `versions are unique across all folders, including test migrations`() {
        val all = moduleFolders().flatMap(::migrations) + migrations(testMigrationRoot)

        MigrationConventions.checkUniqueVersions(all.map { it.name }).shouldBeEmpty()
    }

    @Test
    fun `statements only touch the module's own schema`() {
        moduleFolders()
            .flatMap { folder ->
                migrations(folder).flatMap {
                    MigrationConventions.checkStatements(folder.name, it.name, it.readText())
                }
            }.shouldBeEmpty()
    }

    // Testes negativos: cada regra recusa o erro que deve recusar.

    @Test
    fun `rejects malformed or impossible file names`() {
        MigrationConventions.checkFileName("V2026_10_13_1200__create_x.sql").shouldBeEmpty()
        MigrationConventions.checkFileName("V1__create_x.sql") shouldHaveSize 1
        MigrationConventions.checkFileName("V2026_10_13_1200_create_x.sql") shouldHaveSize 1
        MigrationConventions.checkFileName("V2026_10_13_1200__Create-X.sql") shouldHaveSize 1
        MigrationConventions.checkFileName("R__views.sql") shouldHaveSize 1
        MigrationConventions.checkFileName("V2026_02_30_1200__create_x.sql") shouldHaveSize 1
        MigrationConventions.checkFileName("V2026_10_13_2460__create_x.sql") shouldHaveSize 1
    }

    @Test
    fun `rejects folders that are not modules`() {
        MigrationConventions.checkFolderName("shared", setOf("shared")).shouldBeEmpty()
        MigrationConventions.checkFolderName("billing", setOf("shared")) shouldHaveSize 1
        MigrationConventions.checkFolderName("Front-Desk", setOf("Front-Desk")) shouldHaveSize 1
    }

    @Test
    fun `rejects the same version in two module folders`() {
        val files = listOf("V2026_10_13_1200__create_rooms.sql", "V2026_10_13_1200__create_guests.sql")

        MigrationConventions.checkUniqueVersions(files) shouldHaveSize 1
        MigrationConventions.checkUniqueVersions(files.take(1)).shouldBeEmpty()
    }

    @Test
    fun `rejects unqualified or foreign schema objects and schema creation`() {
        val ok =
            """
            -- create table guests.ignored_in_comment (id uuid);
            CREATE TABLE rooms.room (id UUID PRIMARY KEY);
            CREATE INDEX IF NOT EXISTS room_idx ON rooms.room (id);
            ALTER TABLE ONLY rooms.room ADD COLUMN code TEXT;
            CREATE OR REPLACE VIEW rooms.report_rooms AS SELECT id FROM rooms.room;
            CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;
            """.trimIndent()
        MigrationConventions.checkStatements("rooms", "ok.sql", ok).shouldBeEmpty()

        val violations =
            listOf(
                "CREATE TABLE room (id UUID);",
                "create table guests.guest (id uuid);",
                "CREATE UNIQUE INDEX i ON guests.guest (id);",
                "DROP VIEW IF EXISTS report_rooms;",
                "CREATE SCHEMA rooms;",
            )
        violations.forEach { MigrationConventions.checkStatements("rooms", "x.sql", it) shouldHaveSize 1 }
    }

    @Test
    fun `accepts references, triggers, inserts, grants and drops inside the module schema`() {
        val ok =
            """
            CREATE TABLE rooms.allocation (room_id UUID NOT NULL REFERENCES rooms.room (id));
            ALTER TABLE rooms.allocation ADD CONSTRAINT fk_room FOREIGN KEY (room_id) REFERENCES rooms.room(id);
            CREATE TRIGGER room_touch BEFORE INSERT OR UPDATE ON rooms.room
                FOR EACH ROW EXECUTE FUNCTION rooms.touch();
            CREATE OR REPLACE TRIGGER room_audit AFTER DELETE ON rooms.room FOR EACH ROW EXECUTE FUNCTION rooms.audit();
            INSERT INTO rooms.room_status (code) VALUES ('CLEAN'), ('DIRTY');
            GRANT SELECT ON rooms.report_rooms TO reporting;
            GRANT SELECT (id, code) ON TABLE rooms.room, rooms.room_type TO reporting;
            GRANT EXECUTE ON FUNCTION rooms.touch(uuid, text) TO app;
            GRANT USAGE ON SCHEMA rooms TO reporting;
            REVOKE UPDATE, DELETE ON ALL TABLES IN SCHEMA rooms FROM app;
            DROP TABLE IF EXISTS rooms.old_room, rooms.old_room_type;
            """.trimIndent()

        MigrationConventions.checkStatements("rooms", "ok.sql", ok).shouldBeEmpty()
    }

    @Test
    fun `rejects foreign or unqualified references, triggers, inserts, grants and drop lists`() {
        val violations =
            listOf(
                "CREATE TABLE rooms.stay (guest_id UUID REFERENCES guests.guest (id));",
                "CREATE TABLE rooms.allocation (room_id UUID REFERENCES room (id));",
                "ALTER TABLE rooms.stay ADD FOREIGN KEY (guest_id) REFERENCES guests.guest(id);",
                "CREATE TRIGGER t BEFORE UPDATE ON guests.guest FOR EACH ROW EXECUTE FUNCTION rooms.touch();",
                "CREATE TRIGGER t BEFORE UPDATE ON room FOR EACH ROW EXECUTE FUNCTION rooms.touch();",
                "INSERT INTO room_status (code) VALUES ('CLEAN');",
                "INSERT INTO guests.guest (id) VALUES (gen_random_uuid());",
                "GRANT SELECT ON room TO reporting;",
                "GRANT SELECT ON TABLE rooms.room, guests.guest TO reporting;",
                "GRANT USAGE ON SCHEMA guests TO reporting;",
                "REVOKE UPDATE ON ALL TABLES IN SCHEMA guests FROM app;",
                "DROP TABLE rooms.old_room, guests.guest;",
                "DROP TABLE IF EXISTS rooms.old_room, old_room_type CASCADE;",
            )

        assertSoftly {
            violations.forEach { sql ->
                withClue(sql) { MigrationConventions.checkStatements("rooms", "x.sql", sql) shouldHaveSize 1 }
            }
        }
    }
}
