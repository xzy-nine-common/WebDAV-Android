package github.xzynine.webdav

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private fun Element.childrenByTagName(tagName: String): List<Element> {
    // 使用 getElementsByTag 确保包含冒号的标签名不会触发 CSS 解析错误
    return getElementsByTag(tagName)
}

internal fun Document.findNS(tag: String, nsPrefix: String?): List<Element> {
    val tagName = if (nsPrefix.isNullOrBlank()) tag else "$nsPrefix:$tag"
    return childrenByTagName(tagName)
}

internal fun Document.findNSPrefix(nsUri: String): String? {
    // Jsoup 不真正处理命名空间；这里尝试通过根标签的属性获取前缀
    val root = children().firstOrNull() ?: return null
    val attrs = root.attributes().asList()
    val candidate = attrs.firstOrNull { it.key.startsWith("xmlns:") && it.value == nsUri }
    return candidate?.key?.substringAfter("xmlns:")
}

internal fun Element.findNS(tag: String, nsPrefix: String?): List<Element> {
    val tagName = if (nsPrefix.isNullOrBlank()) tag else "$nsPrefix:$tag"
    return childrenByTagName(tagName)
}
