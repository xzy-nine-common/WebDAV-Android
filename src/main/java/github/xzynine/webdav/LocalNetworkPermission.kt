package github.xzynine.webdav

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17（SDK 37）本地网络访问限制（Local Network Protection）适配工具。
 *
 * 背景：从 Android 17 开始，以 SDK 37 或更高版本为目标平台的应用，默认被禁止访问
 * 本地网络（局域网）。访问局域网内的 WebDAV 服务器属于受限制场景，必须先获得
 * `ACCESS_LOCAL_NETWORK` 运行时权限；未授予时 TCP 连接只会超时、UDP 会返回 EPERM，
 * 表现为"连不上"却没有明确错误。
 *
 * 拆分权限模型：
 * - 目标 SDK >= 37：本地网络默认被屏蔽，必须声明并请求 [PERMISSION]。
 * - 目标 SDK < 37：拥有 INTERNET 权限即隐式获得本地网络访问权，
 *   此时既不要声明也不要请求 [PERMISSION]，[isGranted] 直接返回 true。
 *
 * 本工具类同时提供目标地址判定（[isLocalNetworkUrl]）与授权状态判定（[isGranted]），
 * 宿主应用只需在访问局域网地址前调用 [needsRequest] 决定是否弹系统权限框。
 */
object LocalNetworkPermission {

    /**
     * 本地网络访问权限常量。
     *
     * 使用字符串字面量而非 `Manifest.permission.ACCESS_LOCAL_NETWORK`，
     * 以便本库在较低 compileSdk 下也能编译（低版本平台上该权限不存在也无意义）。
     */
    const val PERMISSION: String = "android.permission.ACCESS_LOCAL_NETWORK"

    /**
     * 本地网络限制开始强制执行的 SDK 版本（Android 17）。
     */
    private const val ENFORCEMENT_SDK_INT = 37

    /**
     * 当前平台是否强制执行本地网络限制（Android 17 及以上）。
     */
    fun isEnforced(): Boolean = Build.VERSION.SDK_INT >= ENFORCEMENT_SDK_INT

    /**
     * 判断目标地址是否属于本地网络（局域网）。
     *
     * 判定依据（仅做静态判定，不触发 DNS 解析，避免额外网络开销与超时）：
     * - IPv4 私有网段：10.0.0.0/8、172.16.0.0/12、192.168.0.0/16
     * - IPv4 链路本地：169.254.0.0/16
     * - IPv6 唯一本地地址：fc00::/7（fc00:: ~ fdff::）
     * - IPv6 链路本地：fe80::/10
     * - mDNS 主机名：以 `.local` 结尾
     *
     * 域名（如 example.com）无法静态判定其解析结果是否为局域网地址，
     * 因此返回 false，交由运行时的失败识别兜底（见 [SyncFailureKind.LOCAL_NETWORK]）。
     *
     * @param url WebDAV 地址，支持 http/https/dav/davs 及带端口、带认证信息的写法
     * @return 目标是否为本地网络地址
     */
    fun isLocalNetworkUrl(url: String?): Boolean {
        val host = extractHost(url) ?: return false

        // mDNS 域名（如 nas.local）必定位于本地网络
        if (host.endsWith(".local")) return true

        val address = host.trim('[', ']')
        if (address.contains(':')) {
            return isLocalIpv6(address)
        }
        return isLocalIpv4(address)
    }

    /**
     * 是否已获得本地网络访问权限。
     *
     * 未强制执行的平台（Android 16 及以下）由 INTERNET 权限隐式授予，恒为 true。
     */
    fun isGranted(context: Context): Boolean {
        if (!isEnforced()) return true
        return runCatching {
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(true)
    }

    /**
     * 访问给定地址前是否需要请求本地网络权限。
     *
     * @param context 用于检查授权状态的上下文
     * @param url 待访问的 WebDAV 地址
     * @return true 表示目标位于局域网且尚未授权，宿主应请求 [PERMISSION]
     */
    fun needsRequest(context: Context, url: String?): Boolean {
        return isLocalNetworkUrl(url) && !isGranted(context)
    }

    /**
     * 从 URL 中提取主机名，失败时返回 null。
     */
    private fun extractHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = runCatching {
            val withoutScheme = url.substringAfter("://", url)
            val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            // 去掉 user:pass@ 认证信息
            authority.substringAfterLast('@')
                // 去掉端口号（IPv6 地址本身带冒号，先按方括号处理）
                .let {
                    if (it.startsWith("[")) {
                        it.substringAfter(']', it)
                    } else {
                        it.substringBefore(':')
                    }
                }
                .trim()
        }.getOrNull()
        return host?.takeIf { it.isNotEmpty() }
    }

    /**
     * 判断 IPv4 字面量是否位于私有网段。
     */
    private fun isLocalIpv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 169 && octets[1] == 254 -> true
            else -> false
        }
    }

    /**
     * 判断 IPv6 字面量是否位于本地网段（ULA / 链路本地）。
     */
    private fun isLocalIpv6(address: String): Boolean {
        val lower = address.lowercase()
        // 环回地址 ::1 不属于本地网络限制范围
        if (lower == "::1") return false
        val head = lower.substringBefore('/')
        return head.startsWith("fe80") ||
            head.startsWith("fc") ||
            head.startsWith("fd") ||
            head.startsWith("::fc") ||
            head.startsWith("::fd")
    }
}
