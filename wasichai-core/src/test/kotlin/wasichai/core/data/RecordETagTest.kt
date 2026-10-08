package wasichai.core.data

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.PreconditionFailedException
import wasichai.core.common.ValidationException
import java.time.Instant
import java.util.UUID

// ADR-051: the ETag is the quoted updatedAt; If-Match is read by strong comparison (RFC 9110 13.1.1)
class RecordETagTest {
    private val read = Instant.parse("2026-10-07T10:15:30.123456Z")
    private val other = Instant.parse("2026-10-07T10:15:31Z")

    @Test
    fun `the etag is the quoted updatedAt as the json writes it`() {
        assertThat(RecordETag.of(read)).isEqualTo("\"2026-10-07T10:15:30.123456Z\"")
        assertThat(RecordETag.of(other)).isEqualTo("\"2026-10-07T10:15:31Z\"")
    }

    @Test
    fun `no header and star are no precondition`() {
        assertThat(RecordETag.parseIfMatch(null)).isNull()
        assertThat(RecordETag.parseIfMatch("*")).isNull()
        assertThat(RecordETag.parseIfMatch("  *  ")).isNull()
    }

    @Test
    fun `an etag the api returned round-trips`() {
        assertThat(RecordETag.parseIfMatch(RecordETag.of(read))).containsExactly(read)
    }

    @Test
    fun `a list accepts any of its strong tags`() {
        assertThat(RecordETag.parseIfMatch("${RecordETag.of(read)}, ${RecordETag.of(other)}")).containsExactly(read, other)
        assertThat(RecordETag.parseIfMatch("${RecordETag.of(read)},${RecordETag.of(other)} ,")).containsExactly(read, other)
    }

    @Test
    fun `a weak tag or a value this api never issued matches nothing`() {
        assertThat(RecordETag.parseIfMatch("W/${RecordETag.of(read)}")).isEmpty()
        assertThat(RecordETag.parseIfMatch("\"v42\"")).isEmpty()
        assertThat(RecordETag.parseIfMatch("W/${RecordETag.of(read)}, ${RecordETag.of(other)}")).containsExactly(other)
    }

    @Test
    fun `a malformed header is a 400 on If-Match`() {
        listOf(
            "",
            "   ",
            // the app's old workaround: updatedAt unquoted
            "2026-10-07T10:15:30.123456Z",
            "\"unterminated",
            "\"a b\"",
            "*, ${RecordETag.of(read)}",
            "${RecordETag.of(read)} junk",
            "w/${RecordETag.of(read)}"
        ).forEach { raw ->
            assertThatThrownBy { RecordETag.parseIfMatch(raw) }
                .`as`(raw)
                .isInstanceOf(ValidationException::class.java)
                .satisfies({ assertThat((it as ValidationException).violations.single().field).isEqualTo("If-Match") })
        }
    }

    @Test
    fun `stale is a 412 naming the header`() {
        val stale = RecordETag.stale(UUID.randomUUID())

        assertThat(stale).isInstanceOf(PreconditionFailedException::class.java)
        assertThat(stale.status.value()).isEqualTo(412)
        assertThat(stale.violations.single().field).isEqualTo("If-Match")
    }
}
