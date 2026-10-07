package wasichai.core.platform

import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.r2dbc.core.DatabaseClient
import java.util.UUID

class BindsTest {
    private val spec = mock(DatabaseClient.GenericExecuteSpec::class.java, Answers.RETURNS_SELF)

    // the driver encodes a null by its class: a primitive boolean has no codec
    @Test
    fun `a null binds as a null of its boxed type`() {
        val enabled: Boolean? = null

        spec.bindNullable("enabled", enabled)

        verify(spec).bindNull("enabled", Boolean::class.javaObjectType)
        verifyNoMoreInteractions(spec)
    }

    @Test
    fun `a value binds as itself`() {
        val id = UUID.randomUUID()

        spec.bindNullable("id", id)

        verify(spec).bind("id", id)
        verify(spec, never()).bindNull("id", UUID::class.java)
    }

    @Test
    fun `a null of a type known only at run time binds as that type`() {
        spec.bindNullable("a0", null, Long::class.javaObjectType)

        verify(spec).bindNull("a0", Long::class.javaObjectType)
        verifyNoMoreInteractions(spec)
    }

    @Test
    fun `a value of a type known only at run time binds as itself`() {
        spec.bindNullable("a0", 42L, Long::class.javaObjectType)

        verify(spec).bind("a0", 42L)
        verifyNoMoreInteractions(spec)
    }
}
