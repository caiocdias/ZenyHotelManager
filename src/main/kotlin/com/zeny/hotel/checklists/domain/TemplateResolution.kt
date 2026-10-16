package com.zeny.hotel.checklists.domain

import com.zeny.hotel.checklists.ApplicabilityRule
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.SubjectAttributes
import java.time.Instant

/** Um modelo ativo e publicado do contexto pedido, com a aplicabilidade da última versão publicada. */
data class ResolutionCandidate(
    val templateId: ChecklistTemplateId,
    val createdAt: Instant,
    val publishedVersion: Int,
    val applicability: List<ApplicabilityRule>,
)

/**
 * Escolha do modelo de um sujeito (RF-CHK-04): vale o modelo com uma regra que casa com os atributos, o mais
 * específico (a regra com mais critérios restritos); sem nenhum, o modelo geral (sem regras). Empate: o criado
 * primeiro (estável: publicar outro modelo não muda a escolha). Um modelo com regras, nenhuma das quais casa,
 * não vale para o sujeito.
 */
object TemplateResolution {
    fun choose(
        candidates: List<ResolutionCandidate>,
        subject: SubjectAttributes,
    ): ResolutionCandidate? =
        candidates
            .mapNotNull { candidate -> score(candidate.applicability, subject)?.let { candidate to it } }
            .sortedWith(
                compareByDescending<Pair<ResolutionCandidate, Int>> { it.second }
                    .thenBy { it.first.createdAt }
                    .thenBy {
                        it.first.templateId.value
                            .toString()
                    },
            ).firstOrNull()
            ?.first

    /** A especificidade com que o modelo vale para o sujeito: `0` no geral, `null` se não vale. */
    fun score(
        rules: List<ApplicabilityRule>,
        subject: SubjectAttributes,
    ): Int? = if (rules.isEmpty()) 0 else rules.filter { it.matches(subject) }.maxOfOrNull { it.specificity }
}
