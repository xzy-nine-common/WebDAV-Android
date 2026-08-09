package github.xzynine.webdav

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

internal inline fun OkHttpClient.newCallResponse(builder: Request.Builder.() -> Unit): Response {
    val requestBuilder = Request.Builder()
    requestBuilder.builder()
    val request = requestBuilder.build()
    val call = newCall(request)
    return call.execute()
}

internal fun ResponseBody.text(): String? = runCatching { string() }.getOrNull()
