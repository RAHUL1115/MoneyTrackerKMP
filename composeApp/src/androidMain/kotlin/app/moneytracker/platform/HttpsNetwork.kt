package app.moneytracker.platform

import app.moneytracker.core.Network

/** Plain HTTPS GETs with short timeouts; used only for exchange rates. */
object HttpsNetwork : Network {
    override suspend fun httpGet(url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("User-Agent", "MoneyTracker")
                if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }
}
