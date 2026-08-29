package app.myzel394.alibi.db

import androidx.datastore.core.Serializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime

class AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): AppSettings {
        return try {
            decode(input.readBytes().decodeToString())
        } catch (error: SerializationException) {
            error.printStackTrace()
            defaultValue
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(encode(t).encodeToByteArray())
    }

    companion object {
        private const val LEGACY_INTERVAL_DURATION = 60 * 1000L
        private const val LEGACY_AUDIO_BIT_RATE = 320000

        // Older settings omitted fields whose values matched the old defaults. Add those
        // values before decoding so changing Kotlin defaults does not change existing users'
        // preferences. Writes include defaults, making this migration unambiguous thereafter.
        private val json = Json { encodeDefaults = true }

        fun encode(settings: AppSettings): String {
            return json.encodeToString(AppSettings.serializer(), settings)
        }

        fun decode(data: String): AppSettings {
            return json.decodeFromJsonElement(
                AppSettings.serializer(),
                withLegacyDefaults(Json.parseToJsonElement(data)),
            )
        }

        private fun withLegacyDefaults(element: JsonElement): JsonObject {
            val settings = element.jsonObject.toMutableMap()

            if ("intervalDuration" !in settings) {
                settings["intervalDuration"] = JsonPrimitive(LEGACY_INTERVAL_DURATION)
            }

            val audioSettings = settings["audioRecorderSettings"]?.jsonObject?.toMutableMap()
                ?: mutableMapOf()
            if ("bitRate" !in audioSettings) {
                audioSettings["bitRate"] = JsonPrimitive(LEGACY_AUDIO_BIT_RATE)
            }
            if ("samplingRate" !in audioSettings) {
                audioSettings["samplingRate"] = JsonNull
            }
            settings["audioRecorderSettings"] = JsonObject(audioSettings)

            return JsonObject(settings)
        }
    }
}

class LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalDateTime {
        return LocalDateTime.parse(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(value.toString())
    }
}
