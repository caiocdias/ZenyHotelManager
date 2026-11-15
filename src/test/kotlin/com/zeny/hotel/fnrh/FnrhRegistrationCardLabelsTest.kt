package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRegistrationCardLabels
import com.zeny.hotel.frontdesk.RegistrationCardLabels
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate

/** A porta dos rótulos da ficha de registro, lida do catálogo de domínios da FNRH (M6 E15, D-E15-1 e D-E15-2). */
@IntegrationTest
class FnrhRegistrationCardLabelsTest(
    @Autowired private val labels: RegistrationCardLabels,
    @Autowired private val context: ApplicationContext,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @Test
    fun `seeded codes come with their labels`() {
        labels.travelReason("LAZER_FERIAS") shouldBe "Lazer/Férias"
        labels.transportMode("AUTOMOVEL") shouldBe "Automóvel"
    }

    @Test
    fun `a code outside the catalog has no label`() {
        labels.travelReason("LAZER").shouldBeNull()
        labels.transportMode("automovel").shouldBeNull()
        labels.transportMode("AUTOMOVEL ").shouldBeNull()
        labels.travelReason("").shouldBeNull()
    }

    @Test
    fun `a label changed in the catalog is read on the next call`() {
        val where = "WHERE domain = 'TRAVEL_REASONS' AND code = 'NEGOCIOS'"
        val original = jdbc.queryForObject("SELECT label FROM fnrh.domain_value $where", String::class.java)!!
        try {
            jdbc.update("UPDATE fnrh.domain_value SET label = 'Negócios (teste)' $where")

            labels.travelReason("NEGOCIOS") shouldBe "Negócios (teste)"
        } finally {
            jdbc.update("UPDATE fnrh.domain_value SET label = ? $where", original)
        }
        labels.travelReason("NEGOCIOS") shouldBe original
    }

    @Test
    fun `the context has exactly one implementation and it is the fnrh one`() {
        val beans = context.getBeansOfType(RegistrationCardLabels::class.java)

        beans.size shouldBe 1
        beans.values.single().javaClass shouldBe FnrhRegistrationCardLabels::class.java
    }
}
