package com.taskly.shared

import kotlinx.serialization.json.Json

/** Единая конфигурация JSON для sync-протокола и моделей (сервер + клиенты). */
val sharedJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
    allowSpecialFloatingPointValues = false
}