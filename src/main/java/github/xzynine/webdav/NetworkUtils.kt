package github.xzynine.webdav

import java.net.URI

internal object NetworkUtils {
    fun getBaseUrl(url: String): String {
        return try {
            val uri = URI(url)
            URI(uri.scheme, uri.userInfo, uri.host, uri.port, null, null, null).toString()
        } catch (_: Exception) {
            url
        }
    }

    fun getAbsoluteURL(baseUrl: String, href: String): String {
        return try {
            val base = URI(baseUrl)
            base.resolve(href).toString()
        } catch (_: Exception) {
            href
        }
    }
}
