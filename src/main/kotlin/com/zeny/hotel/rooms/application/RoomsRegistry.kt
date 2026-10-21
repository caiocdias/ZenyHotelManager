package com.zeny.hotel.rooms.application

import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.AreaKind
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomRegistryChange
import com.zeny.hotel.rooms.RoomRegistryChanged
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsErrorCodes
import com.zeny.hotel.rooms.RoomsPermissions
import com.zeny.hotel.rooms.RoomsRealtime
import com.zeny.hotel.rooms.domain.Area
import com.zeny.hotel.rooms.domain.Room
import com.zeny.hotel.rooms.domain.RoomFeature
import com.zeny.hotel.rooms.domain.RoomType
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.RealtimeTopic
import com.zeny.hotel.shared.requireCurrentVersion
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Cadastro de características, tipos de UH, UHs e áreas comuns (RF-UH-01 a 03). Consultar exige
 * `rooms.registry.read`; escrever, `rooms.registry.manage`, e as edições conferem a versão da última
 * leitura (`If-Match`). Nada é apagado: tudo é só desativado (o banco recusa a exclusão).
 *
 * Regras entre registros: uma característica nova num tipo ou numa UH precisa existir (`400
 * UNKNOWN_ROOM_FEATURE`) e estar ativa (`422 ROOM_FEATURE_INACTIVE`); a que já estava fica mesmo
 * desativada depois. Uma UH ativa só fica num tipo ativo: criar a UH, mudar o tipo dela e reativá-la
 * exigem o tipo ativo (`422 ROOM_TYPE_INACTIVE`), e o tipo com UHs ativas não é desativado (`422
 * ROOM_TYPE_HAS_ACTIVE_ROOMS`). As duas pontas travam a linha do tipo, então não há corrida.
 *
 * Criar uma UH, trocar o tipo dela, desativá-la e reativá-la publicam [RoomRegistryChanged] pelo outbox, na transação
 * (H4 do M4): o inventário por tipo do `reservations` recalcula o total. As outras edições não publicam nada.
 */
@Service
@Suppress("TooManyFunctions") // um caso de uso por operação do cadastro
class RoomsRegistryService(
    private val store: RoomsStore,
    private val directory: RoomsDirectory,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // --- Características -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun features(
        active: Boolean?,
        page: PageParams,
    ): CursorPage<RoomFeatureView> {
        authorization.require(RoomsPermissions.REGISTRY_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = directory.features(active, after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.code, it.id.toString()) }
    }

    @Transactional(readOnly = true)
    fun feature(id: RoomFeatureId): RoomFeatureView {
        authorization.require(RoomsPermissions.REGISTRY_READ)
        return findFeature(id).view()
    }

    @Audited(RoomsAudit.CREATE_FEATURE)
    @Transactional
    fun createFeature(
        code: String,
        name: String,
    ): RoomFeatureView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val feature = RoomFeature.create(code, name, clock.instant())
        audit.created(RoomsAudit.ROOM_FEATURE, feature.id.value) { feature.auditState() }
        if (!store.tryAdd(feature)) throw featureCodeInUse()
        return feature.view()
    }

    @Audited(RoomsAudit.UPDATE_FEATURE)
    @Transactional
    fun updateFeature(
        id: RoomFeatureId,
        expectedVersion: Long,
        code: String,
        name: String,
    ): RoomFeatureView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val feature = findFeature(id)
        requireCurrentVersion(expectedVersion, checkNotNull(feature.version))
        audit.track(RoomsAudit.ROOM_FEATURE, id.value) { feature.auditState() }
        if (feature.update(code, name, clock.instant()) && !store.tryFlush(RoomsConstraints.FEATURE_CODE)) {
            throw featureCodeInUse()
        }
        return feature.view()
    }

    @Audited(RoomsAudit.DEACTIVATE_FEATURE)
    @Transactional
    fun deactivateFeature(id: RoomFeatureId): RoomFeatureView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val feature = findFeature(id)
        audit.track(RoomsAudit.ROOM_FEATURE, id.value) { feature.auditState() }
        if (feature.deactivate(clock.instant())) store.flush()
        return feature.view()
    }

    @Audited(RoomsAudit.REACTIVATE_FEATURE)
    @Transactional
    fun reactivateFeature(id: RoomFeatureId): RoomFeatureView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val feature = findFeature(id)
        audit.track(RoomsAudit.ROOM_FEATURE, id.value) { feature.auditState() }
        if (feature.reactivate(clock.instant())) store.flush()
        return feature.view()
    }

    // --- Tipos de UH -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun roomTypes(
        active: Boolean?,
        page: PageParams,
    ): CursorPage<RoomTypeView> {
        authorization.require(RoomsPermissions.REGISTRY_READ)
        val after = page.decodedCursor()?.let { it.long(0) to it.uuid(1) }
        val rows = directory.roomTypes(active, after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.displayOrder.toString(), it.id.toString()) }
    }

    @Transactional(readOnly = true)
    fun roomType(id: RoomTypeId): RoomTypeView {
        authorization.require(RoomsPermissions.REGISTRY_READ)
        return findRoomType(id).view()
    }

    @Audited(RoomsAudit.CREATE_ROOM_TYPE)
    @Transactional
    fun createRoomType(command: RoomTypeCommand): RoomTypeView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val roomType = RoomType.create(command.details, clock.instant())
        requireAssignableFeatures(command.featureIds, current = emptySet())
        audit.created(RoomsAudit.ROOM_TYPE, roomType.id.value) { roomType.auditState() }
        if (!store.tryAdd(roomType)) throw roomTypeCodeInUse()
        return roomType.view()
    }

    @Audited(RoomsAudit.UPDATE_ROOM_TYPE)
    @Transactional
    fun updateRoomType(
        id: RoomTypeId,
        expectedVersion: Long,
        command: RoomTypeCommand,
    ): RoomTypeView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val roomType = findRoomType(id)
        requireCurrentVersion(expectedVersion, checkNotNull(roomType.version))
        audit.track(RoomsAudit.ROOM_TYPE, id.value) { roomType.auditState() }
        requireAssignableFeatures(command.featureIds, current = roomType.featureIds)
        if (roomType.update(command.details, clock.instant()) && !store.tryFlush(RoomsConstraints.ROOM_TYPE_CODE)) {
            throw roomTypeCodeInUse()
        }
        return roomType.view()
    }

    @Audited(RoomsAudit.DEACTIVATE_ROOM_TYPE)
    @Transactional
    fun deactivateRoomType(id: RoomTypeId): RoomTypeView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val roomType = store.roomTypeForUpdate(id) ?: throw roomTypeNotFound()
        audit.track(RoomsAudit.ROOM_TYPE, id.value) { roomType.auditState() }
        if (roomType.active && store.hasActiveRooms(id)) {
            throw BusinessRuleException(
                RoomsErrorCodes.ROOM_TYPE_HAS_ACTIVE_ROOMS,
                "O tipo de UH tem UHs ativas: desative-as ou mude-as de tipo antes.",
            )
        }
        if (roomType.deactivate(clock.instant())) store.flush()
        return roomType.view()
    }

    @Audited(RoomsAudit.REACTIVATE_ROOM_TYPE)
    @Transactional
    fun reactivateRoomType(id: RoomTypeId): RoomTypeView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val roomType = store.roomTypeForUpdate(id) ?: throw roomTypeNotFound()
        audit.track(RoomsAudit.ROOM_TYPE, id.value) { roomType.auditState() }
        if (roomType.reactivate(clock.instant())) store.flush()
        return roomType.view()
    }

    // --- UHs -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun rooms(
        roomTypeId: RoomTypeId?,
        active: Boolean?,
        page: PageParams,
    ): CursorPage<RoomView> {
        authorization.require(RoomsPermissions.REGISTRY_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = directory.rooms(roomTypeId, active, after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.number.lowercase(), it.id.toString()) }
    }

    @Transactional(readOnly = true)
    fun room(id: RoomId): RoomView {
        authorization.require(RoomsPermissions.REGISTRY_READ)
        return findRoom(id).view()
    }

    @Audited(RoomsAudit.CREATE_ROOM)
    @Transactional
    fun createRoom(command: RoomCommand): RoomView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val room = Room.create(command.details, clock.instant())
        requireActiveRoomType(command.roomTypeId)
        requireAssignableFeatures(command.featureIds, current = emptySet())
        audit.created(RoomsAudit.ROOM, room.id.value) { room.auditState() }
        if (!store.tryAdd(room)) throw roomNumberInUse()
        publishRegistry(room, RoomRegistryChange.CREATED, null)
        log.info("UH {} cadastrada", room.id)
        return room.view()
    }

    @Audited(RoomsAudit.UPDATE_ROOM)
    @Transactional
    fun updateRoom(
        id: RoomId,
        expectedVersion: Long,
        command: RoomCommand,
    ): RoomView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val room = findRoom(id)
        requireCurrentVersion(expectedVersion, checkNotNull(room.version))
        audit.track(RoomsAudit.ROOM, id.value) { room.auditState() }
        val previousType = room.roomTypeId
        if (command.roomTypeId != previousType) requireActiveRoomType(command.roomTypeId)
        requireAssignableFeatures(command.featureIds, current = room.featureIds)
        if (room.update(command.details, clock.instant()) && !store.tryFlush(RoomsConstraints.ROOM_NUMBER)) {
            throw roomNumberInUse()
        }
        if (room.roomTypeId != previousType) publishRegistry(room, RoomRegistryChange.ROOM_TYPE_CHANGED, previousType)
        return room.view()
    }

    @Audited(RoomsAudit.DEACTIVATE_ROOM)
    @Transactional
    fun deactivateRoom(id: RoomId): RoomView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val room = findRoom(id)
        audit.track(RoomsAudit.ROOM, id.value) { room.auditState() }
        if (room.active && room.occupancy == OccupancyStatus.OCCUPIED) {
            // Uma mudança de ocupação simultânea muda a versão: a gravação abaixo responde 412.
            throw BusinessRuleException(RoomsErrorCodes.ROOM_OCCUPIED, "A UH está ocupada e não pode ser desativada.")
        }
        if (room.deactivate(clock.instant())) {
            store.flush()
            publishRegistry(room, RoomRegistryChange.DEACTIVATED, null)
            log.info("UH {} desativada", id)
        }
        return room.view()
    }

    @Audited(RoomsAudit.REACTIVATE_ROOM)
    @Transactional
    fun reactivateRoom(id: RoomId): RoomView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val room = findRoom(id)
        audit.track(RoomsAudit.ROOM, id.value) { room.auditState() }
        if (!room.active) {
            requireActiveRoomType(room.roomTypeId)
            room.reactivate(clock.instant())
            store.flush()
            publishRegistry(room, RoomRegistryChange.REACTIVATED, null)
        }
        return room.view()
    }

    // --- Áreas comuns ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun areas(
        kind: AreaKind?,
        active: Boolean?,
        page: PageParams,
    ): CursorPage<AreaView> {
        authorization.require(RoomsPermissions.REGISTRY_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = directory.areas(kind, active, after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.name.lowercase(), it.id.toString()) }
    }

    @Transactional(readOnly = true)
    fun area(id: AreaId): AreaView {
        authorization.require(RoomsPermissions.REGISTRY_READ)
        return findArea(id).view()
    }

    @Audited(RoomsAudit.CREATE_AREA)
    @Transactional
    fun createArea(command: AreaCommand): AreaView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val area = Area.create(command.details, clock.instant())
        audit.created(RoomsAudit.AREA, area.id.value) { area.auditState() }
        if (!store.tryAdd(area)) throw areaNameInUse()
        return area.view()
    }

    @Audited(RoomsAudit.UPDATE_AREA)
    @Transactional
    fun updateArea(
        id: AreaId,
        expectedVersion: Long,
        command: AreaCommand,
    ): AreaView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val area = findArea(id)
        requireCurrentVersion(expectedVersion, checkNotNull(area.version))
        audit.track(RoomsAudit.AREA, id.value) { area.auditState() }
        if (area.update(command.details, clock.instant()) && !store.tryFlush(RoomsConstraints.AREA_NAME)) {
            throw areaNameInUse()
        }
        return area.view()
    }

    @Audited(RoomsAudit.DEACTIVATE_AREA)
    @Transactional
    fun deactivateArea(id: AreaId): AreaView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val area = findArea(id)
        audit.track(RoomsAudit.AREA, id.value) { area.auditState() }
        if (area.deactivate(clock.instant())) store.flush()
        return area.view()
    }

    @Audited(RoomsAudit.REACTIVATE_AREA)
    @Transactional
    fun reactivateArea(id: AreaId): AreaView {
        authorization.require(RoomsPermissions.REGISTRY_MANAGE)
        val area = findArea(id)
        audit.track(RoomsAudit.AREA, id.value) { area.auditState() }
        if (area.reactivate(clock.instant())) store.flush()
        return area.view()
    }

    // --- Apoio -------------------------------------------------------------------------------------

    /** Publica [RoomRegistryChanged] com a UH já gravada (a versão é a do `flush`). */
    private fun publishRegistry(
        room: Room,
        change: RoomRegistryChange,
        previousType: RoomTypeId?,
    ) {
        events.publishEvent(
            RoomRegistryChanged(
                room.id.value,
                room.roomTypeId.value,
                previousType?.value,
                room.active,
                change,
                checkNotNull(room.version),
                room.updatedAt,
            ),
        )
    }

    private fun findFeature(id: RoomFeatureId) =
        store.feature(id)
            ?: throw NotFoundException(RoomsErrorCodes.ROOM_FEATURE_NOT_FOUND, "Característica não encontrada.")

    private fun findRoomType(id: RoomTypeId) = store.roomType(id) ?: throw roomTypeNotFound()

    private fun findRoom(id: RoomId) =
        store.room(id) ?: throw NotFoundException(RoomsErrorCodes.ROOM_NOT_FOUND, "UH não encontrada.")

    private fun findArea(id: AreaId) =
        store.area(id) ?: throw NotFoundException(RoomsErrorCodes.AREA_NOT_FOUND, "Área não encontrada.")

    /** O tipo existe (`400`) e está ativo (`422`), travado até o fim da transação. */
    private fun requireActiveRoomType(id: RoomTypeId) {
        val roomType =
            store.roomTypeForUpdate(id)
                ?: throw invalidReference(
                    RoomsErrorCodes.UNKNOWN_ROOM_TYPE,
                    "roomTypeId",
                    "O tipo de UH informado não existe.",
                )
        if (!roomType.active) {
            throw BusinessRuleException(RoomsErrorCodes.ROOM_TYPE_INACTIVE, "O tipo de UH informado está desativado.")
        }
    }

    /** As características novas (as que não estão em [current]) existem (`400`) e estão ativas (`422`). */
    private fun requireAssignableFeatures(
        ids: Set<RoomFeatureId>,
        current: Set<RoomFeatureId>,
    ) {
        val added = ids - current
        if (added.isEmpty()) return
        val found = store.features(added)
        if (found.size < added.size) {
            throw invalidReference(
                RoomsErrorCodes.UNKNOWN_ROOM_FEATURE,
                "featureIds",
                "Alguma característica informada não existe.",
            )
        }
        if (found.any { !it.active }) {
            throw BusinessRuleException(
                RoomsErrorCodes.ROOM_FEATURE_INACTIVE,
                "Alguma característica informada está desativada.",
            )
        }
    }

    private fun roomTypeNotFound() =
        NotFoundException(RoomsErrorCodes.ROOM_TYPE_NOT_FOUND, "Tipo de UH não encontrado.")

    private fun featureCodeInUse() =
        ConflictException(
            RoomsErrorCodes.ROOM_FEATURE_CODE_ALREADY_IN_USE,
            "Já existe uma característica com este código.",
        )

    private fun roomTypeCodeInUse() =
        ConflictException(RoomsErrorCodes.ROOM_TYPE_CODE_ALREADY_IN_USE, "Já existe um tipo de UH com este código.")

    private fun roomNumberInUse() =
        ConflictException(RoomsErrorCodes.ROOM_NUMBER_ALREADY_IN_USE, "Já existe uma UH com este número.")

    private fun areaNameInUse() =
        ConflictException(RoomsErrorCodes.AREA_NAME_ALREADY_IN_USE, "Já existe uma área com este nome.")

    private fun invalidReference(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}

@Configuration(proxyBeanMethods = false)
class RoomsPermissionsConfiguration {
    @Bean
    fun roomsPermissions(): ModulePermissions = RoomsPermissions

    /** O tópico `rooms` do canal de tempo real (status das UHs e períodos OOO/OOS). */
    @Bean
    fun roomsRealtimeTopic(): RealtimeTopic = RoomsRealtime.TOPIC
}
