package ua.starky.audiokniga.data.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /**
     * [headers] — дополнительные заголовки источника (например, `Referer` и
     * `X-Requested-With` для AJAX-запросов). [userAgent] переопределяется редко:
     * некоторые сайты по нему выбирают вёрстку, и разбор настроен под настольную.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = USER_AGENT,
    ): Response = withContext(Dispatchers.IO) {
        val request = try {
            val builder = Request.Builder().url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json, application/xml, text/xml, text/html, */*")
                .header("Accept-Language", "ru,en;q=0.8")
            headers.forEach { (name, value) -> builder.header(name, value) }
            builder.build()
        } catch (e: IllegalArgumentException) {
            throw ProviderException("адрес записан неверно: $url", e)
        }

        val call = client.newCall(request)
        val response = try {
            // Через enqueue, а не execute: так запрос можно отменить вместе с экраном
            // или по таймауту корутины, а не ждать его в заблокированном потоке.
            call.await()
        } catch (e: IOException) {
            throw ProviderException(networkMessage(e, url), e)
        }

        response.use { result ->
            if (!result.isSuccessful) {
                throw ProviderException("ответил ${result.code} ${result.message}")
            }
            val body = result.body?.string()
                ?: throw ProviderException("вернул пустой ответ")
            Response(
                body = body,
                contentType = result.header("Content-Type").orEmpty().lowercase(),
                url = result.request.url.toString(),
            )
        }
    }

    private suspend fun Call.await(): okhttp3.Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                // Гонка с отменой: ответ мог прийти уже после того, как он не нужен.
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    suspend fun getString(url: String, userAgent: String = USER_AGENT): String =
        get(url, userAgent = userAgent).body

    /** Человеческая формулировка вместо стектрейса OkHttp — её увидит пользователь. */
    private fun networkMessage(e: IOException, url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        val where = host?.let { " ($it)" } ?: ""
        val reason = e.message.orEmpty()
        return when {
            reason.contains("Unable to resolve host", true) ||
                e is java.net.UnknownHostException ->
                "— такого адреса нет или он не резолвится$where"
            e is java.net.SocketTimeoutException -> "не ответил вовремя$where"
            e is java.net.ConnectException -> "не принимает соединение$where"
            reason.contains("CLEARTEXT", true) -> "требует обычный http, а он запрещён системой"
            reason.contains("SSL", true) || reason.contains("certificate", true) ->
                "не прошёл проверку сертификата$where"
            else -> "недоступен$where: ${reason.take(120)}"
        }
    }
}
