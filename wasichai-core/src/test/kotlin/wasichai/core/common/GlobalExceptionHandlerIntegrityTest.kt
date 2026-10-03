package wasichai.core.common

import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerIntegrityTest {
    private val handler = GlobalExceptionHandler("https://wasichai.dev/problems")

    @Test
    fun `a foreign key violation is a conflict`() {
        val ex = DataIntegrityViolationException("insert", R2dbcDataIntegrityViolationException("fk", "23503"))

        val problem = handler.handleIntegrity(ex)

        assertThat(problem.status).isEqualTo(HttpStatus.CONFLICT.value())
        assertThat(problem.detail).contains("does not exist any more")
    }

    @Test
    fun `any other integrity violation stays a 500`() {
        val ex = DataIntegrityViolationException("insert", R2dbcDataIntegrityViolationException("not null", "23502"))

        assertThat(handler.handleIntegrity(ex).status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value())
        assertThat(handler.handleIntegrity(DataIntegrityViolationException("no cause")).status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value())
    }
}
