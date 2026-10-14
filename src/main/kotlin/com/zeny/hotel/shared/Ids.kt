package com.zeny.hotel.shared

import jakarta.persistence.AttributeConverter
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Id tipado (D15). Cada agregado declara o seu como `value class` sobre um UUIDv7:
 *
 * ```kotlin
 * @JvmInline
 * value class RoomId(override val value: UUID) : TypedId {
 *     companion object { fun new() = RoomId(UuidV7.generate()) }
 * }
 * ```
 *
 * Num atributo de entidade (`@Id val id: RoomId`), o Kotlin guarda o UUID desembrulhado e o
 * Hibernate o mapeia direto para `uuid`, sem conversor. Por isso buscas pelo `EntityManager`
 * e repositórios Spring Data usam o UUID (`em.find(Room::class.java, id.value)`,
 * `JpaRepository<Room, UUID>`). Só em coleções e tipos genéricos o id chega embrulhado ao
 * Hibernate; aí entra um [TypedIdConverter].
 */
interface TypedId {
    val value: UUID
}

/**
 * Conversor JPA de um id tipado para `uuid`, para os casos em que o Hibernate recebe o id
 * embrulhado (ex.: `@ElementCollection val roomIds: Set<RoomId>`):
 * `class RoomIdConverter : TypedIdConverter<RoomId>(::RoomId)` e `@Convert` na coleção.
 */
@Suppress("AbstractClassCanBeConcreteClass") // o JPA instancia a subclasse, que tem construtor sem argumentos
abstract class TypedIdConverter<T : TypedId>(
    private val wrap: (UUID) -> T,
) : AttributeConverter<T, UUID> {
    override fun convertToDatabaseColumn(attribute: T?): UUID? = attribute?.value

    override fun convertToEntityAttribute(dbData: UUID?): T? = dbData?.let(wrap)
}

/** Gerador padrão de UUIDv7 do sistema, sobre o relógio UTC da JVM. */
object UuidV7 {
    private val generator = UuidV7Generator(Clock.systemUTC())

    fun generate(): UUID = generator.generate()
}

/**
 * UUIDv7 (RFC 9562 §5.7): 48 bits de milissegundos Unix, versão, 12 bits de contador e 62
 * bits aleatórios. É monotônico no processo (RFC 9562 §6.2, método 1): no mesmo
 * milissegundo, ou se o relógio voltar, o contador avança; se ele estourar, o carimbo avança
 * 1 ms. Assim os ids gerados aqui ficam em ordem estrita de geração, tanto no `compareTo` do
 * Java quanto na ordem do tipo `uuid` do PostgreSQL.
 */
class UuidV7Generator(
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    private var lastMillis = Long.MIN_VALUE
    private var counter = 0

    @Synchronized
    fun generate(): UUID {
        val now = clock.millis()
        if (now > lastMillis) {
            lastMillis = now
            // Semente aleatória na metade de baixo do contador: sobra espaço para avançar.
            counter = random.nextInt(COUNTER_SEED_BOUND)
        } else {
            counter++
            if (counter > COUNTER_MAX) {
                lastMillis++
                counter = 0
            }
        }
        val mostSignificant = (lastMillis shl TIMESTAMP_SHIFT) or VERSION_BITS or counter.toLong()
        val leastSignificant = (random.nextLong() and RANDOM_MASK) or VARIANT_BITS
        return UUID(mostSignificant, leastSignificant)
    }

    private companion object {
        const val TIMESTAMP_SHIFT = 16
        const val VERSION_BITS = 0x7000L
        const val COUNTER_MAX = 0xFFF
        const val COUNTER_SEED_BOUND = 0x800
        const val RANDOM_MASK = 0x3FFF_FFFF_FFFF_FFFFL
        const val VARIANT_BITS = Long.MIN_VALUE // bits 10 no topo: variante da RFC 9562
    }
}

private const val UUID_VERSION_7 = 7
private const val UUID_TIMESTAMP_SHIFT = 16

/** Se o UUID é da versão 7 com a variante da RFC 9562. */
fun UUID.isVersion7(): Boolean = version() == UUID_VERSION_7 && variant() == 2

/** O instante (em milissegundos) gravado num UUIDv7. */
fun UUID.v7Instant(): Instant {
    require(isVersion7()) { "Não é um UUIDv7: $this" }
    return Instant.ofEpochMilli(mostSignificantBits ushr UUID_TIMESTAMP_SHIFT)
}
