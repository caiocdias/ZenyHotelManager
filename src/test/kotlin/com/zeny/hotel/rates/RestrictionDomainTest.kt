package com.zeny.hotel.rates

import com.zeny.hotel.rates.RestrictionField.CLOSED
import com.zeny.hotel.rates.RestrictionField.CLOSED_TO_ARRIVAL
import com.zeny.hotel.rates.RestrictionField.MAX_STAY
import com.zeny.hotel.rates.RestrictionField.MIN_ADVANCE_DAYS
import com.zeny.hotel.rates.RestrictionField.MIN_STAY
import com.zeny.hotel.rates.domain.EffectiveRestriction
import com.zeny.hotel.rates.domain.RestrictionChanges
import com.zeny.hotel.rates.domain.RestrictionKey
import com.zeny.hotel.rates.domain.RestrictionPatch
import com.zeny.hotel.rates.domain.RestrictionValues
import com.zeny.hotel.rates.domain.Restrictions
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Restrições de venda (M4 E03, RF-TAR-05): a precedência campo a campo da restrição do tipo de UH sobre a geral do
 * plano (inclusive o `false` que abre exceção), a edição parcial, as faixas e o mínimo maior que o máximo, a
 * conferência da combinação geral × tipo e o que uma edição muda.
 */
class RestrictionDomainTest {
    private val day: LocalDate = LocalDate.of(2026, 12, 24)
    private val type = RoomTypeId(UuidV7.generate())
    private val other = RoomTypeId(UuidV7.generate())

    @Test
    fun `the room type restriction overrides the general one field by field, and an empty field inherits it`() {
        val general = RestrictionValues(closed = true, closedToArrival = true, minStay = 3, maxAdvanceDays = 90)
        val specific = RestrictionValues(closed = false, minStay = 1)

        val effective = EffectiveRestriction.of(general, specific)

        effective.closed shouldBe false // a exceção do tipo abre a data fechada na geral
        effective.closedToArrival shouldBe true // herdado da geral
        effective.closedToDeparture shouldBe false // nenhuma informa: não restringe
        effective.minStay shouldBe 1
        effective.maxStay shouldBe null
        effective.maxAdvanceDays shouldBe 90
        effective.fromRoomType shouldBe setOf(CLOSED, MIN_STAY)

        // Sem a do tipo, vale a geral; sem nenhuma, nada restringe.
        EffectiveRestriction.of(general, null).let {
            it.closed shouldBe true
            it.minStay shouldBe 3
            it.fromRoomType.shouldBeEmpty()
        }
        EffectiveRestriction.of(null, specific).closed shouldBe false
        EffectiveRestriction.NONE.closed shouldBe false
        EffectiveRestriction.NONE.minStay shouldBe null
        // Um tipo fechado sobre uma geral aberta também prevalece.
        EffectiveRestriction.of(RestrictionValues(closed = false), RestrictionValues(closed = true)).closed shouldBe
            true
    }

    @Test
    fun `an edit sets only the fields given, clears the ones asked for and keeps the others`() {
        val recorded = RestrictionValues(closedToArrival = true, minStay = 2, maxStay = 7)
        val patch = RestrictionPatch.of(RestrictionValues(closed = true, minStay = 3), setOf(MAX_STAY))

        patch.applyTo(recorded) shouldBe RestrictionValues(closed = true, closedToArrival = true, minStay = 3)
        patch.applyTo(null) shouldBe RestrictionValues(closed = true, minStay = 3)
        patch.fields shouldBe setOf(CLOSED, MIN_STAY, MAX_STAY)
        RestrictionPatch
            .of(
                RestrictionValues.NONE,
                setOf(CLOSED_TO_ARRIVAL, MIN_STAY, MAX_STAY),
            ).applyTo(recorded) shouldBe
            RestrictionValues.NONE
    }

    @Test
    fun `values out of range, negative ones and a minimum above the maximum are refused with stable codes`() {
        fun refusal(set: RestrictionValues): Pair<String, String> =
            shouldThrow<InvalidInputException> { RestrictionPatch.of(set, emptySet()) }.let {
                it.code.value to it.violations.single().field
            }

        refusal(RestrictionValues(minStay = -1)) shouldBe ("RESTRICTION_INVALID" to "set.minStay")
        refusal(RestrictionValues(minStay = 0)) shouldBe ("RESTRICTION_INVALID" to "set.minStay")
        refusal(RestrictionValues(maxStay = 366)) shouldBe ("RESTRICTION_INVALID" to "set.maxStay")
        refusal(RestrictionValues(minAdvanceDays = -1)) shouldBe ("RESTRICTION_INVALID" to "set.minAdvanceDays")
        refusal(RestrictionValues(maxAdvanceDays = 731)) shouldBe ("RESTRICTION_INVALID" to "set.maxAdvanceDays")
        refusal(RestrictionValues(minStay = 5, maxStay = 4)) shouldBe ("RESTRICTION_RANGE_INVALID" to "set.maxStay")
        refusal(RestrictionValues(minAdvanceDays = 10, maxAdvanceDays = 9)) shouldBe
            ("RESTRICTION_RANGE_INVALID" to "set.maxAdvanceDays")

        // Os limites valem.
        RestrictionPatch.of(
            RestrictionValues(minStay = 1, maxStay = 365, minAdvanceDays = 0, maxAdvanceDays = 730),
            emptySet(),
        )
        RestrictionPatch.of(RestrictionValues(minStay = 4, maxStay = 4), emptySet())

        shouldThrow<InvalidInputException> { RestrictionPatch.of(RestrictionValues(minStay = 2), setOf(MIN_STAY)) }
            .let { (it.code.value to it.violations.single().field) shouldBe ("RESTRICTION_INVALID" to "clear") }
        shouldThrow<InvalidInputException> { RestrictionPatch.of(RestrictionValues.NONE, emptySet()) }
            .let { (it.code.value to it.violations.single().field) shouldBe ("RESTRICTION_INVALID" to "set") }
    }

    @Test
    fun `a combination with the minimum above the maximum is refused, in the same row or across general and type`() {
        fun coherent(rows: Map<RestrictionKey, RestrictionValues>) =
            Restrictions.requireCoherent(rows, listOf(type, other), listOf(day))

        coherent(mapOf(RestrictionKey(null, day) to RestrictionValues(minStay = 2, maxStay = 5)))
        // A geral pede 3 noites; o tipo, no máximo 2: a efetiva do tipo seria incoerente.
        shouldThrow<BusinessRuleException> {
            coherent(
                mapOf(
                    RestrictionKey(null, day) to RestrictionValues(minStay = 3),
                    RestrictionKey(type, day) to RestrictionValues(maxStay = 2),
                ),
            )
        }.code.value shouldBe "RESTRICTION_RANGE_CONFLICT"
        // O tipo que também baixa o mínimo resolve.
        coherent(
            mapOf(
                RestrictionKey(null, day) to RestrictionValues(minStay = 3),
                RestrictionKey(type, day) to RestrictionValues(minStay = 1, maxStay = 2),
            ),
        )
        shouldThrow<BusinessRuleException> {
            coherent(
                mapOf(
                    RestrictionKey(null, day) to RestrictionValues(maxAdvanceDays = 10),
                    RestrictionKey(other, day) to RestrictionValues(minAdvanceDays = 11),
                ),
            )
        }.code.value shouldBe "RESTRICTION_RANGE_CONFLICT"
        // A mesma linha incoerente (o pedido mexeu só num dos dois).
        shouldThrow<BusinessRuleException> {
            coherent(mapOf(RestrictionKey(type, day) to RestrictionValues(minStay = 4, maxStay = 3)))
        }
    }

    @Test
    fun `an edit tells what it creates, changes and removes, and the fields that changed`() {
        val next = day.plusDays(1)
        val third = day.plusDays(2)
        val existing =
            mapOf(
                RestrictionKey(type, day) to RestrictionValues(minStay = 2),
                RestrictionKey(type, next) to RestrictionValues(minStay = 2, closed = true),
                RestrictionKey(type, third) to RestrictionValues(closed = true),
            )
        val keys = listOf(day, next, third, day.plusDays(3)).map { RestrictionKey(type, it) }

        val changes =
            RestrictionChanges.of(
                keys,
                existing,
                RestrictionPatch.of(RestrictionValues(closed = true), setOf(MIN_STAY)),
            )

        changes.created shouldContainExactly listOf(RestrictionKey(type, day.plusDays(3)))
        changes.changed shouldContainExactly listOf(RestrictionKey(type, day), RestrictionKey(type, next))
        changes.removed.shouldBeEmpty() // a terceira já estava fechada e sem mínimo: não muda
        changes.fields shouldBe setOf(CLOSED, MIN_STAY)
        changes.after.getValue(RestrictionKey(type, day)) shouldBe RestrictionValues(closed = true)

        val removal = RestrictionChanges.of(keys, existing, RestrictionPatch.of(RestrictionValues.NONE, setOf(CLOSED)))
        removal.removed shouldContainExactly listOf(RestrictionKey(type, third))
        removal.changed shouldContainExactly listOf(RestrictionKey(type, next))
        removal.created.shouldBeEmpty()
        removal.fields shouldBe setOf(CLOSED)

        RestrictionChanges
            .of(keys, existing, RestrictionPatch.of(RestrictionValues.NONE, setOf(MIN_ADVANCE_DAYS)))
            .isEmpty() shouldBe true
    }
}
