package com.taskly.shared.domain.model

import com.taskly.shared.currentEpochMillis
import kotlin.jvm.JvmInline
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.random.Random

/**
 * UUIDv7 (RFC 9562): 48-битный timestamp в миллисекундах + случайные биты.
 * Все идентификаторы сущностей Taskly — UUIDv7, генерируются на клиенте,
 * поэтому задачу можно создать и сразу править офлайн (SPEC F-1, ARCH 5.1).
 */
@Serializable(with = UuidSerializer::class)
@JvmInline
value class Uuid private constructor(val value: String) : Comparable<Uuid> {

    override fun compareTo(other: Uuid): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        private const val HEX = "0123456789abcdef"
        private val PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun fromString(raw: String): Uuid {
            require(PATTERN.matches(raw)) { "Invalid UUID string: '$raw'" }
            return Uuid(raw.lowercase())
        }

        /** Генерация UUIDv7 с корректными version (7) и variant (10xx). */
        fun generate(): Uuid {
            val b = ByteArray(16)
            var ts = currentEpochMillis()
            for (i in 5 downTo 0) {
                b[i] = (ts and 0xFF).toByte()
                ts = ts ushr 8
            }
            b[6] = ((0x7 shl 4) or (Random.nextInt(16) and 0x0F)).toByte()
            b[7] = randomByte()
            b[8] = ((0b10 shl 6) or (randomByte().toInt() and 0x3F)).toByte()
            for (i in 9..15) b[i] = randomByte()
            return Uuid(format(b))
        }

        private fun randomByte(): Byte = (Random.nextInt(256) - 128).toByte()

        private fun format(b: ByteArray): String {
            val sb = StringBuilder(36)
            for (i in 0..15) {
                val v = b[i].toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
                if (i == 3 || i == 5 || i == 7 || i == 9) sb.append('-')
            }
            return sb.toString()
        }
    }
}

/** Сериализация Uuid как строки (стандартный 8-4-4-4-12 формат). */
object UuidSerializer : KSerializer<Uuid> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.taskly.shared.domain.model.Uuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uuid) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): Uuid = Uuid.fromString(decoder.decodeString())
}