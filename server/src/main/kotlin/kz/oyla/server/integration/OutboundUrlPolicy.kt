package kz.oyla.server.integration

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale

data class ValidatedIntegrationUrl(val uri: URI, val normalized: String)

/**
 * A deliberately narrow outbound policy: integrations have a single trusted
 * base URL, no redirects, and every DNS answer must be public before a call.
 */
class OutboundUrlPolicy(
    private val production: Boolean,
    private val allowUnsafeDevelopmentOutbound: Boolean = false,
    private val resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName
) {
    init { require(!production || !allowUnsafeDevelopmentOutbound) { "Unsafe integration outbound override is forbidden in production" } }

    fun validateBaseUrl(value: String): ValidatedIntegrationUrl {
        val raw = value.trim()
        if (raw.isEmpty() || raw.length > MaxUrlLength) throw OutboundUrlException.Invalid
        val input = runCatching { URI(raw) }.getOrElse { throw OutboundUrlException.Invalid }
        val scheme = input.scheme?.lowercase(Locale.ROOT) ?: throw OutboundUrlException.Invalid
        if (scheme !in allowedSchemes()) throw OutboundUrlException.Invalid
        if (!input.isAbsolute || input.host.isNullOrBlank() || input.userInfo != null || input.fragment != null || input.rawQuery != null) throw OutboundUrlException.Invalid
        if (input.port !in -1..65535) throw OutboundUrlException.Invalid
        val host = input.host.lowercase(Locale.ROOT).trimEnd('.').takeIf { it.isNotBlank() } ?: throw OutboundUrlException.Invalid
        if (!allowUnsafeDevelopmentOutbound && (host == "localhost" || host.endsWith(".localhost"))) throw OutboundUrlException.Blocked
        val path = input.rawPath.orEmpty().trimEnd('/').let { if (it.isBlank()) "" else it }
        val normalized = URI(scheme, null, host, input.port, path, null, null)
        verifyResolvedHost(normalized.host)
        return ValidatedIntegrationUrl(normalized, normalized.toString())
    }

    /** Called before every retry as DNS can change after settings were saved. */
    fun verifyResolvedHost(host: String) {
        val addresses = try { resolver(host) } catch (_: UnknownHostException) { throw OutboundUrlException.Unreachable }
        catch (_: SecurityException) { throw OutboundUrlException.Unreachable }
        if (addresses.isEmpty()) throw OutboundUrlException.Unreachable
        if (!allowUnsafeDevelopmentOutbound && addresses.any(::isForbiddenAddress)) throw OutboundUrlException.Blocked
    }

    fun endpoint(base: URI, path: String): URI {
        require(path.startsWith('/'))
        return URI(base.toString().trimEnd('/') + path)
    }

    private fun allowedSchemes(): Set<String> = if (production || !allowUnsafeDevelopmentOutbound) setOf("https") else setOf("https", "http")

    private fun isForbiddenAddress(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> {
            val b = address.address.map { it.toInt() and 0xff }
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress ||
                b[0] == 0 || b[0] == 10 || b[0] == 100 && b[1] in 64..127 || b[0] == 127 || b[0] == 169 && b[1] == 254 ||
                b[0] == 172 && b[1] in 16..31 || b[0] == 192 && b[1] == 0 || b[0] == 192 && b[1] == 168 || b[0] == 198 && b[1] in 18..19 || b[0] >= 224
        }
        is Inet6Address -> {
            val b = address.address.map { it.toInt() and 0xff }
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress ||
                (b[0] and 0xfe) == 0xfc || (b[0] == 0xfe && (b[1] and 0xc0) == 0x80)
        }
        else -> true
    }

    private companion object { const val MaxUrlLength = 2048 }
}

sealed class OutboundUrlException : RuntimeException() {
    data object Invalid : OutboundUrlException()
    data object Blocked : OutboundUrlException()
    data object Unreachable : OutboundUrlException()
}
