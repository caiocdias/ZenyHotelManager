package com.zeny.hotel.notifications.application

import com.zeny.hotel.notifications.NotificationPreference
import com.zeny.hotel.notifications.NotificationTypeCode
import com.zeny.hotel.notifications.domain.PreferenceRules
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.CurrentActor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A preferência de um tipo como o usuário a vê: com a descrição e se é obrigatório. */
data class NotificationPreferenceView(
    val type: NotificationTypeCode,
    val description: String,
    val mandatory: Boolean,
    val inApp: Boolean,
    val push: Boolean,
)

/**
 * As preferências do próprio usuário (RF-NOT-02), uma por tipo do catálogo, sem exigir permissão: listar (com os
 * padrões, painel e push ligados) e definir as dos tipos enviados (os outros ficam como estavam).
 */
@Service
class NotificationPreferencesService(
    private val preferences: PreferenceStore,
    private val catalog: NotificationTypeCatalog,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
) {
    @Transactional(readOnly = true)
    fun list(): List<NotificationPreferenceView> = viewsOf(preferences.of(currentActor.current().userId))

    @Audited(NotificationsAudit.UPDATE_PREFERENCES)
    @Transactional
    fun update(changes: List<NotificationPreference>): List<NotificationPreferenceView> {
        val user = currentActor.current().userId
        PreferenceRules.validate(changes, catalog::find)
        val before = preferences.of(user).associateBy { it.type }
        val changed =
            changes.filter { change ->
                val previous = PreferenceRules.effective(catalog.require(change.type), before[change.type])
                (previous != change).also { differs ->
                    if (differs) {
                        audit.record(
                            NotificationsAudit.PREFERENCE,
                            "$user:${change.type}",
                            previous.state(),
                            change.state(),
                        )
                    }
                }
            }
        if (changed.isEmpty()) audit.noChanges()
        preferences.save(user, changes)
        return viewsOf(preferences.of(user))
    }

    private fun viewsOf(stored: List<NotificationPreference>): List<NotificationPreferenceView> {
        val byType = stored.associateBy { it.type }
        return catalog.all.map { type ->
            val preference = PreferenceRules.effective(type, byType[type.code])
            NotificationPreferenceView(type.code, type.description, type.mandatory, preference.inApp, preference.push)
        }
    }

    private fun NotificationPreference.state() = mapOf("inApp" to inApp, "push" to push)
}
