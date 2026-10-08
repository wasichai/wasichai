package wasichai.files

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContentSnifferTest {
    @Test
    fun `images are known by their magic numbers, whatever the client says`() {
        assertThat(ContentSniffer.detect(FileFixtures.PNG, "application/pdf")).isEqualTo("image/png")
        assertThat(ContentSniffer.detect(FileFixtures.JPEG, null)).isEqualTo("image/jpeg")
        assertThat(ContentSniffer.detect(FileFixtures.WEBP, "image/png")).isEqualTo("image/webp")
        assertThat(ContentSniffer.detect("GIF89a....".toByteArray(), null)).isEqualTo("image/gif")
        assertThat(ContentSniffer.detect(FileFixtures.PDF, "image/png")).isEqualTo("application/pdf")
    }

    @Test
    fun `a claim the bytes do not back is not believed`() {
        // an html page sent as a png is no png
        assertThat(ContentSniffer.detect("<html><script>alert(1)</script>".toByteArray(), "image/png")).isEqualTo("application/octet-stream")
        // nor is a text claim on binary bytes
        assertThat(ContentSniffer.detect(byteArrayOf(0, 1, 2, 3), "text/plain")).isEqualTo("application/octet-stream")
        assertThat(ContentSniffer.detect(byteArrayOf(0xC3.toByte(), 0x28), "text/csv")).isEqualTo("application/octet-stream")
    }

    @Test
    fun `text and zip-based office files take the claim only when the bytes agree`() {
        assertThat(ContentSniffer.detect("a;b\n1;2\n".toByteArray(), "text/csv; charset=utf-8")).isEqualTo("text/csv")
        assertThat(ContentSniffer.detect("hola".toByteArray(), "text/html")).isEqualTo("application/octet-stream")
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertThat(ContentSniffer.detect(FileFixtures.ZIP, docx)).isEqualTo(docx)
        assertThat(ContentSniffer.detect(FileFixtures.ZIP, "image/png")).isEqualTo("application/zip")
        assertThat(ContentSniffer.detect("not a zip".toByteArray(), docx)).isEqualTo("application/octet-stream")
    }

    @Test
    fun `the allow-list takes exact types and type wildcards, null allows anything`() {
        assertThat(ContentSniffer.allowed("image/png", listOf("image/png", "image/jpeg"))).isTrue()
        assertThat(ContentSniffer.allowed("image/gif", listOf("image/png", "image/jpeg"))).isFalse()
        assertThat(ContentSniffer.allowed("image/gif", listOf("image/*"))).isTrue()
        assertThat(ContentSniffer.allowed("application/pdf", listOf("image/*"))).isFalse()
        assertThat(ContentSniffer.allowed("application/octet-stream", null)).isTrue()
    }
}
