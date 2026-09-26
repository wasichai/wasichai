package wasichai.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import wasichai.core.common.ValidationException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

// an automation is the one place where the platform calls out on someone else's say-so, so the
// url is checked twice: when it is saved, and again before every call.
@Component
class WebhookSender(
    private val properties: AutomationProperties
) {
    // own builder: boot 4 hands out no WebClient.Builder bean, and no shared defaults is the point
    private val client = WebClient.builder().build()

    suspend fun post(
        url: String,
        body: Map<String, Any?>
    ): String {
        val target = validate(url)
        val status =
            client
                .post()
                .uri(target)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(properties.webhookTimeout)
                .awaitSingle()
                .statusCode
        return "POST $target -> ${status.value()}"
    }

    // fails on save too, where the message reaches the admin building the automation
    suspend fun validate(url: String?): URI {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) {
            throw ValidationException("Webhook has no url", "actions", "url is required for a WEBHOOK action")
        }
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri?.host == null || uri.scheme !in SCHEMES) {
            throw ValidationException("Invalid webhook url '$raw'", "actions", "must be an absolute http(s) url")
        }
        reject(uri)
        return uri
    }

    // dns resolution is blocking, and a name that resolves to 127.0.0.1 is the whole attack
    private suspend fun reject(uri: URI) {
        if (properties.allowPrivateWebhooks) return
        val addresses =
            withContext(Dispatchers.IO) {
                runCatching { InetAddress.getAllByName(uri.host).toList() }
                    .getOrElse { throw UnknownHostException("Webhook host '${uri.host}' does not resolve") }
            }
        val blocked =
            addresses.firstOrNull {
                it.isLoopbackAddress ||
                    it.isSiteLocalAddress ||
                    it.isLinkLocalAddress ||
                    it.isAnyLocalAddress ||
                    it.isMulticastAddress
            }
        if (blocked != null) {
            throw ValidationException(
                "Webhook host '${uri.host}' resolves to the private address ${blocked.hostAddress}",
                "actions",
                "set wasichai.automation.allow-private-webhooks to call it anyway"
            )
        }
    }

    private companion object {
        val SCHEMES = setOf("http", "https")
    }
}
