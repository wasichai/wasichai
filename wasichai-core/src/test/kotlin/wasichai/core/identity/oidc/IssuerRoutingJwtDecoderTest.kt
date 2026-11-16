package wasichai.core.identity.oidc

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import wasichai.core.platform.Oidc
import java.net.InetSocketAddress
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec

class IssuerRoutingJwtDecoderTest {
    private val realmIssuer = "https://auth.example.org/realms/peru"
    private val rsa: RSAKey = RSAKeyGenerator(2048).generate()
    private val otherRsa: RSAKey = RSAKeyGenerator(2048).generate()
    private val secret = ByteArray(32) { it.toByte() }

    // real jwks endpoint, so the production factory is what gets tested
    private val jwksHits = AtomicInteger()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/certs") { ex ->
                jwksHits.incrementAndGet()
                val body = JWKSet(rsa.toPublicJWK()).toString().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    private val realmDecoder =
        realmJwtDecoder(Oidc(issuerUri = realmIssuer, clientId = "caja", jwkSetUri = "http://127.0.0.1:${server.address.port}/certs")).decoder

    @AfterEach
    fun stop() = server.stop(0)

    private val localDecoder =
        NimbusReactiveJwtDecoder.withSecretKey(SecretKeySpec(secret, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build()
    private val router = IssuerRoutingJwtDecoder(RealmJwtDecoder(realmIssuer, realmDecoder), localDecoder, "wasichai")

    private fun claims(
        issuer: String,
        aud: List<String>,
        exp: Boolean = true
    ) = JWTClaimsSet
        .Builder()
        .issuer(issuer)
        .subject("user-1")
        .audience(aud)
        .apply { if (exp) expirationTime(Date(System.currentTimeMillis() + 60_000)) }
        .build()

    private fun sign(
        claims: JWTClaimsSet,
        signer: JWSSigner,
        alg: JWSAlgorithm
    ): String = SignedJWT(JWSHeader(alg), claims).also { it.sign(signer) }.serialize()

    private fun realmToken(
        aud: List<String>,
        key: RSAKey = rsa
    ) = sign(claims(realmIssuer, aud), RSASSASigner(key), JWSAlgorithm.RS256)

    @Test
    fun `a realm token for this client is accepted`() {
        StepVerifier
            .create(router.decode(realmToken(listOf("caja"))))
            .expectNextMatches { it.subject == "user-1" }
            .verifyComplete()
    }

    @Test
    fun `a realm token for another client of the realm is refused`() {
        StepVerifier
            .create(router.decode(realmToken(listOf("srtm"))))
            .expectErrorSatisfies {
                assertThat(it).isInstanceOf(JwtValidationException::class.java)
                assertThat(it.message).contains("aud").contains("caja")
            }.verify()
    }

    @Test
    fun `a token signed by another key under the realm's issuer is refused`() {
        StepVerifier
            .create(router.decode(realmToken(listOf("caja"), otherRsa)))
            .expectError(BadJwtException::class.java)
            .verify()
    }

    @Test
    fun `the app's own service-account token still decodes`() {
        val token = sign(claims("wasichai", listOf("wasichai")), MACSigner(secret), JWSAlgorithm.HS256)
        StepVerifier
            .create(router.decode(token))
            .expectNextMatches { it.subject == "user-1" }
            .verifyComplete()
    }

    @Test
    fun `an unknown issuer is refused before any key is tried`() {
        val calls = mutableListOf<String>()
        val spy =
            ReactiveJwtDecoder {
                calls += it
                Mono.empty<Jwt>()
            }
        val guarded = IssuerRoutingJwtDecoder(RealmJwtDecoder(realmIssuer, spy), spy, "wasichai")
        val token = sign(claims("https://evil.example", listOf("caja")), RSASSASigner(rsa), JWSAlgorithm.RS256)
        StepVerifier.create(guarded.decode(token)).expectError(BadJwtException::class.java).verify()
        StepVerifier.create(guarded.decode("not-a-jwt")).expectError(BadJwtException::class.java).verify()
        check(calls.isEmpty()) { "delegate called: $calls" }
    }

    @Test
    fun `garbage is refused`() {
        StepVerifier.create(router.decode("not-a-jwt")).expectError(BadJwtException::class.java).verify()
    }

    @Test
    fun `a token without issuer is refused`() {
        val noIss = JWTClaimsSet.Builder().subject("x").build()
        StepVerifier
            .create(router.decode(sign(noIss, RSASSASigner(rsa), JWSAlgorithm.RS256)))
            .expectError(BadJwtException::class.java)
            .verify()
    }

    @Test
    fun `no key is fetched at construction, only on the first realm token`() {
        assertThat(jwksHits.get()).isZero()
        StepVerifier.create(router.decode(realmToken(listOf("caja")))).expectNextCount(1).verifyComplete()
        assertThat(jwksHits.get()).isEqualTo(1)
    }

    @Test
    fun `an HS256 token with the realm issuer is refused`() {
        val token = sign(claims(realmIssuer, listOf("caja")), MACSigner(secret), JWSAlgorithm.HS256)
        StepVerifier.create(router.decode(token)).expectError(BadJwtException::class.java).verify()
    }

    @Test
    fun `an RS256 token with the local issuer is refused`() {
        val token = sign(claims("wasichai", listOf("wasichai")), RSASSASigner(rsa), JWSAlgorithm.RS256)
        StepVerifier.create(router.decode(token)).expectError(BadJwtException::class.java).verify()
    }

    @Test
    fun `a realm token without exp is refused`() {
        val token = sign(claims(realmIssuer, listOf("caja"), exp = false), RSASSASigner(rsa), JWSAlgorithm.RS256)
        StepVerifier.create(router.decode(token)).expectError(BadJwtException::class.java).verify()
    }
}
