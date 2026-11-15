package com.zeny.hotel.fnrh.application

import com.zeny.hotel.fnrh.domain.FnrhDomain
import com.zeny.hotel.fnrh.domain.FnrhDomainCatalog
import com.zeny.hotel.frontdesk.RegistrationCardLabels
import org.springframework.stereotype.Component

/**
 * Os rótulos da ficha de registro vindos do catálogo de domínios da FNRH (M6 E15, D-E15-2). Sem cache: cada ficha lê
 * o catálogo, então um `refresh` aparece na ficha seguinte. O código é buscado exato (maiúsculas contam).
 */
@Component
class FnrhRegistrationCardLabels(
    private val catalog: FnrhDomainCatalog,
) : RegistrationCardLabels {
    override fun travelReason(code: String): String? = label(FnrhDomain.TRAVEL_REASONS, code)

    override fun transportMode(code: String): String? = label(FnrhDomain.TRANSPORT_MODES, code)

    private fun label(
        domain: FnrhDomain,
        code: String,
    ): String? =
        catalog
            .values(domain)
            .firstOrNull { it.code == code }
            ?.label
}
