package wasichai.core.data

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import wasichai.core.common.FieldViolation
import wasichai.core.common.GlobalExceptionHandler
import wasichai.core.common.RetryLaterException
import wasichai.core.common.UnprocessableContentException
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

// ADR-058: the key's form, what makes two requests the same, and a stored answer read back as a record
class IdempotencyKeysTest {
    private val keys = IdempotencyKeysFixtures.none()

    @Test
    fun `a key is 1 to 128 printable ascii characters`() {
        IdempotencyKeys.requireValid("a")
        IdempotencyKeys.requireValid("0b8e6a1c-5f3e-4c1e-9d1a-2f4e8c7b6a50")
        IdempotencyKeys.requireValid("x".repeat(128))
        IdempotencyKeys.requireValid("order 42/retry:1")

        listOf("", "   ", "x".repeat(129), "tab\there", "ñandú", "line\nbreak").forEach { bad ->
            assertThatThrownBy { IdempotencyKeys.requireValid(bad) }
                .`as`("key '%s'", bad)
                .isInstanceOf(ValidationException::class.java)
                .satisfies({ assertThat((it as ValidationException).violations.single().field).isEqualTo("Idempotency-Key") })
        }
    }

    @Test
    fun `the same body in another key order or spacing is the same request`() {
        val one = keys.requestHash("POST", "/api/objects/caso/records", mapOf("attributes" to mapOf("a" to 1, "b" to mapOf("y" to 2, "x" to 3))))
        val two = keys.requestHash("POST", "/api/objects/caso/records", mapOf("attributes" to mapOf("b" to mapOf("x" to 3, "y" to 2), "a" to 1)))

        assertThat(one).isEqualTo(two).hasSize(64)
    }

    @Test
    fun `another value, path or method is another request`() {
        val body = mapOf("attributes" to mapOf("a" to 1))
        val base = keys.requestHash("POST", "/api/objects/caso/records", body)

        assertThat(keys.requestHash("POST", "/api/objects/caso/records", mapOf("attributes" to mapOf("a" to 2)))).isNotEqualTo(base)
        assertThat(keys.requestHash("POST", "/api/objects/caso/records", mapOf("attributes" to mapOf("a" to "1")))).isNotEqualTo(base)
        assertThat(keys.requestHash("POST", "/api/objects/otro/records", body)).isNotEqualTo(base)
        assertThat(keys.requestHash("PUT", "/api/objects/caso/records", body)).isNotEqualTo(base)
    }

    @Test
    fun `a stored answer reads back as the record its json held`() {
        val record =
            RecordResponse(
                id = "0b8e6a1c-5f3e-4c1e-9d1a-2f4e8c7b6a50",
                createdAt = Instant.parse("2026-10-08T10:15:30.123456Z"),
                updatedAt = Instant.parse("2026-10-08T10:15:30.123456Z"),
                attributes = mapOf("codigo" to "C-1", "monto" to BigDecimal("10.50"), "nota" to null),
                state = "ABIERTO",
                sections = mapOf("geometry" to mapOf("ubicacion" to mapOf("type" to "Point")))
            )

        val back = keys.readRecord(keys.write(record))

        assertThat(back.id).isEqualTo(record.id)
        assertThat(back.createdAt).isEqualTo(record.createdAt)
        assertThat(back.updatedAt).isEqualTo(record.updatedAt)
        assertThat(back.state).isEqualTo("ABIERTO")
        assertThat(back.attributes).containsEntry("codigo", "C-1").containsEntry("monto", BigDecimal("10.50")).containsEntry("nota", null)
        assertThat(back.sections).isEqualTo(record.sections)
        // and it writes back to the same json
        assertThat(keys.write(back)).isEqualTo(keys.write(record))
    }

    @Test
    fun `a key in flight answers 409 with Retry-After, another body 422, both as problem json naming the header`() {
        val handler = GlobalExceptionHandler("https://errors.test")

        val inFlight =
            handler.handleRetryLater(
                RetryLaterException("still running", Duration.ofMillis(1500), listOf(FieldViolation(IdempotencyKeys.HEADER, "in flight")))
            )
        assertThat(inFlight.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(inFlight.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2")
        assertThat(inFlight.body!!.status).isEqualTo(409)
        assertThat(inFlight.body!!.properties!!["errors"]).isEqualTo(listOf(FieldViolation(IdempotencyKeys.HEADER, "in flight")))

        val reused = handler.handleWasichai(UnprocessableContentException("used", listOf(FieldViolation(IdempotencyKeys.HEADER, "other body"))))
        assertThat(reused.status).isEqualTo(422)
        assertThat(reused.type.toString()).isEqualTo("https://errors.test/422")
    }
}
