package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.domain.FnrhDocument
import com.zeny.hotel.fnrh.domain.FnrhDocumentType
import com.zeny.hotel.fnrh.domain.FnrhGuest
import com.zeny.hotel.fnrh.domain.FnrhLocalValidator
import com.zeny.hotel.fnrh.domain.FnrhPersonData
import com.zeny.hotel.fnrh.domain.FnrhRetryPolicy
import com.zeny.hotel.fnrh.domain.IbgeMunicipalities
import com.zeny.hotel.fnrh.infrastructure.FnrhProperties
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.ApplicationContext
import org.springframework.core.io.ClassPathResource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** A montagem do envio da FNRH (M5 E08, Task 5): propriedades `zeny.fnrh.submissions.*` e beans que as consomem. */
@IntegrationTest
class FnrhSubmissionConfigurationTest(
    @Autowired private val context: ApplicationContext,
    @Autowired private val properties: FnrhProperties,
    @Autowired private val policy: FnrhRetryPolicy,
    @Autowired private val validator: FnrhLocalValidator,
) {
    @Test
    fun `o application yaml traz os valores de producao do envio`() {
        val sources = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml"))
        val binder = Binder(ConfigurationPropertySources.from(sources))
        val submissions = binder.bind("zeny.fnrh.submissions", FnrhProperties.Submissions::class.java).get()
        submissions.interval shouldBe Duration.ofMinutes(1)
        submissions.initialDelay shouldBe Duration.ofMinutes(1)
        submissions.batchSize shouldBe 50
        submissions.maxAttempts shouldBe 8
        submissions.initialBackoff shouldBe Duration.ofMinutes(1)
        submissions.maxBackoff shouldBe Duration.ofHours(1)
    }

    @Test
    fun `o contexto de teste adia o envio e mantem os demais valores`() {
        val submissions = properties.submissions
        submissions.initialDelay shouldBe Duration.ofHours(24)
        submissions.interval shouldBe Duration.ofMinutes(1)
        submissions.batchSize shouldBe 50
        submissions.maxAttempts shouldBe 8
        submissions.initialBackoff shouldBe Duration.ofMinutes(1)
        submissions.maxBackoff shouldBe Duration.ofHours(1)
    }

    @Test
    fun `a politica de retry sai das propriedades`() {
        policy shouldBe FnrhRetryPolicy(8, Duration.ofMinutes(1), Duration.ofHours(1))
    }

    @Test
    fun `ha um unico IbgeMunicipalities no contexto`() {
        context.getBeansOfType(IbgeMunicipalities::class.java).size shouldBe 1
    }

    @Test
    fun `o validador do contexto acusa municipio inexistente`() {
        val person =
            FnrhPersonData(
                name = "Maria da Silva",
                nationality = "BR",
                gender = "MULHER",
                birthDate = LocalDate.of(1990, 5, 10),
                race = "PARDA",
                document = FnrhDocument(FnrhDocumentType.CPF, "52998224725"),
                residenceCountry = "BR",
                cityIbge = 1,
                state = "SP",
            )
        val guest = FnrhGuest(true, Instant.parse("2026-11-10T15:00:00Z"), "LAZER_FERIAS", "AVIAO", null)
        validator.validate(person, guest, LocalDate.of(2026, 11, 10), true).shouldNotBeEmpty()
    }
}
