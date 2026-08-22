package ua.starky.audiokniga.data.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Часть открытых API (в первую очередь archive.org) режет запросы без внятного
     * User-Agent, поэтому представляемся как обычный браузер и добавляем себя в скобках.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36 Audiokniga/0.2"

    /** Тело ответа вместе с типом содержимого — по нему выбирается разборщик. */
    data class Response(val body: String, val contentType: String, val url: String)

    suspend fun get(url: String): Response = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, application/xml, text/xml, text/html, */*")
                .header("Accept-Language", "ru,en;q=0.8")
                .build()
        } catch (e: IllegalArgumentException) {
            throw ProviderException("адрес записан неверно: $url", e)
        }

        val call = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ProviderException(networkMessage(e, url), e)
        }

        call.use { response ->
            if (!response.isSuccessful) {
                throw ProviderException("ответил ${response.code} ${response.message}")
            }
            val body = response.body?.string()
                ?: throw ProviderException("вернул пустой ответ")
            Response(
                body = body,
                contentType = response.header("Content-Type").orEmpty().lowercase(),
                url = response.request.url.toString(),
            )
        }
    }

    suspend fun getString(url: String): String = get(url).body

    /** Человеческая формулировка вместо стектрейса OkHttp — её увидит пользователь. */
    private fun networkMessage(e: IOException, url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        val where = host?.let { " ($it)" } ?: ""
        val reason = e.message.orEmpty()
        return when {
            reason.contains("Unable to resolve host", true) ||
                e is java.net.UnknownHostException -> "не отвечает$where — проверьте интернет"
            e is java.net.SocketTimeoutException -> "не ответил вовремя$where"
            reason.contains("CLEARTEXT", true) -> "требует обычный http, а он запрещён системой"
            else -> "недоступен$where: ${reason.take(120)}"
        }
    }
}
