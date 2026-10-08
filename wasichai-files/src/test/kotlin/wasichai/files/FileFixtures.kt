package wasichai.files

// the smallest byte strings each sniffer branch knows. not valid images: magic numbers are all that is read.
object FileFixtures {
    val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D) + "IHDR".toByteArray()
    val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10) + "JFIF".toByteArray()
    val WEBP = "RIFF".toByteArray() + byteArrayOf(0x24, 0, 0, 0) + "WEBPVP8 ".toByteArray()
    val PDF = "%PDF-1.7\n%âã\n1 0 obj\n".toByteArray()
    val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0, 0, 0)
}
