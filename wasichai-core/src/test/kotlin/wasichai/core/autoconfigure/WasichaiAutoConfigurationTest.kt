package wasichai.core.autoconfigure

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.reactive.ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.web.cors.reactive.CorsConfigurationSource
import tools.jackson.databind.json.JsonMapper
import wasichai.core.admin.ServiceAccountService
import wasichai.core.audit.AuditLogAdminAudit
import wasichai.core.audit.AuditPage
import wasichai.core.data.NoWorkflowStates
import wasichai.core.data.ObjectDefinitionFixtures
import wasichai.core.data.ObjectWorkflowState
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordReadScope
import wasichai.core.data.RecordReadScopes
import wasichai.core.data.RecordService
import wasichai.core.data.WorkflowStates
import wasichai.core.identity.AdminAudit
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.ServiceAccountTokenService
import wasichai.core.identity.WasichaiJwtKey
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.FieldTypeHandler
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.ObjectActionService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.CorrelationIdWebFilter
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.SystemColumn
import wasichai.core.platform.SystemColumnContributor
import wasichai.core.platform.SystemColumns
import wasichai.core.platform.WasichaiMigrations
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class WasichaiAutoConfigurationTest {
    private val runner =
        ReactiveWebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    ReactiveUserDetailsServiceAutoConfiguration::class.java,
                    // boot's own security/oauth2 defaults: must back off behind ours, not double up
                    ReactiveWebSecurityAutoConfiguration::class.java,
                    ReactiveOAuth2ResourceServerAutoConfiguration::class.java,
                    ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration::class.java,
                    WasichaiPlatformAutoConfiguration::class.java,
                    WasichaiSecurityAutoConfiguration::class.java,
                    WasichaiMetadataAutoConfiguration::class.java,
                    WasichaiDataAutoConfiguration::class.java,
                    WasichaiAdminAutoConfiguration::class.java
                )
            ).withBean(DatabaseClient::class.java, { mock(DatabaseClient::class.java) })
            .withBean(JsonMapper::class.java, { JsonMapper.builder().build() })
            .withPropertyValues("wasichai.database.migrate=false", "wasichai.security.jwt.secret=0123456789abcdef0123456789abcdef")

    private val shape =
        object : FieldTypeHandler {
            override val type = FieldType("SHAPE")

            override fun columnType(field: CustomField) = "text"

            override fun toDatabase(
                field: CustomField,
                value: Any?
            ) = value

            override fun javaType(field: CustomField) = String::class.java

            override fun fromDatabase(
                field: CustomField,
                value: Any?
            ) = value
        }

    @Test
    fun `core wires on its own, with no module and no in-memory user`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(RecordService::class.java)
            assertThat(context).hasSingleBean(ClusterLock::class.java)
            assertThat(context).hasSingleBean(RecordReadScopes::class.java)
            assertThat(context).hasSingleBean(ObjectActionService::class.java)
            assertThat(context).hasSingleBean(ServiceAccountService::class.java)
            assertThat(context).hasSingleBean(ServiceAccountTokenService::class.java)
            // ADR-050: every request gets a correlation id, ahead of the security chain
            assertThat(context).hasSingleBean(CorrelationIdWebFilter::class.java)
            // issue 49 (ADR-049): admin changes go to the audit log
            assertThat(context.getBean(AdminAudit::class.java)).isInstanceOf(AuditLogAdminAudit::class.java)
            assertThat(context.getBean(WorkflowStates::class.java)).isInstanceOf(NoWorkflowStates::class.java)
            // order, not just size: core's twelve types, in ScalarFieldTypes.ALL's declared order
            assertThat(context.getBean(FieldTypeRegistry::class.java).types).containsExactly(
                FieldType.TEXT,
                FieldType.LONG_TEXT,
                FieldType.INTEGER,
                FieldType.DECIMAL,
                FieldType.BOOLEAN,
                FieldType.DATE,
                FieldType.DATETIME,
                FieldType.ENUM,
                FieldType.EMAIL,
                FieldType.URL,
                FieldType.UUID,
                FieldType.RELATION
            )
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values).containsExactly(ModuleMigration.CORE)
            assertThat(context).doesNotHaveBean(WasichaiMigrations::class.java)
            assertThat(context).doesNotHaveBean(ReactiveUserDetailsService::class.java)
            // ReactiveJwtDecoderConfiguration is @ConditionalOnMissingBean(ReactiveJwtDecoder::class)
            // at class level, so boot's own decoder never gets created: exactly one, ours.
            assertThat(context.getBeansOfType(ReactiveJwtDecoder::class.java)).hasSize(1)
            // boot's ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration ALSO contributes its own
            // catch-all SecurityWebFilterChain whenever a ReactiveJwtDecoder bean exists: its
            // @ConditionalOnDefaultWebSecurity condition checks for a SERVLET SecurityFilterChain bean
            // (org.springframework.security.web.SecurityFilterChain), which a pure WebFlux app never
            // has, so it never backs off here (confirmed against Boot 4.1.1's shipped source: both
            // DefaultWebSecurityCondition and the servlet type live under its ".web.servlet" package,
            // reused as-is for the reactive autoconfig). both chains match anyExchange(), so only
            // @Order decides which one the security infra actually evaluates and applies. ours
            // (WasichaiSecurityAutoConfiguration.securityFilterChain) is pinned to @Order(0), verified
            // here directly rather than by bean count, which would be 2 either way.
            assertThat(context.getBeansOfType(SecurityWebFilterChain::class.java)).containsKey("securityFilterChain")
            assertThat(context.beanFactory.findAnnotationOnBean("securityFilterChain", Order::class.java)?.value).isEqualTo(0)
        }
    }

    // issue 52 (ADR-052): a browser on another origin can read the audit list's next-page cursor, and a
    // record's ETag (ADR-051)
    @Test
    fun `cors exposes the audit list's next cursor`() {
        runner.run { context ->
            val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/audit"))
            val config = context.getBean(CorsConfigurationSource::class.java).getCorsConfiguration(exchange)
            assertThat(config!!.exposedHeaders).containsExactly(HttpHeaders.ETAG, AuditPage.NEXT_CURSOR_HEADER)
        }
    }

    // issue 48 (ADR-048): an app's read scopes, every one, in @Order; asked for a person, never for ADMIN
    @Test
    fun `every app RecordReadScope is collected in order`() {
        val asked = mutableListOf<String>()

        fun scope(
            name: String,
            order: Int
        ) = object : RecordReadScope, Ordered {
            override fun getOrder() = order

            override suspend fun criterion(
                caller: AuthenticatedUser,
                definition: ObjectDefinition
            ): RecordCriterion {
                asked += name
                return RecordCriterion { _, _ -> name }
            }
        }
        runner
            .withBean("second", RecordReadScope::class.java, { scope("second", 2) })
            .withBean("first", RecordReadScope::class.java, { scope("first", 1) })
            .run { context ->
                assertThat(context).hasNotFailed()
                val scopes = context.getBean(RecordReadScopes::class.java)
                val person = AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "ana@example.com", listOf("SOCIAL"))
                val admin = person.copy(roles = listOf(AuthenticatedUser.ADMIN_ROLE))
                val definition = ObjectDefinitionFixtures.empty()

                val criteria = runBlocking { scopes.criteria(person, definition) }
                runBlocking { scopes.criteria(admin, definition) }

                assertThat(criteria.map { it.condition(definition) { "?" } }).containsExactly("first", "second")
                assertThat(asked).containsExactly("first", "second")
            }
    }

    @Test
    fun `the dev seed is opt in`() {
        runner.withPropertyValues("wasichai.seed.dev=true").run { context ->
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values)
                .containsExactlyInAnyOrder(ModuleMigration.CORE, ModuleMigration.CORE_SEED)
        }
    }

    @Test
    fun `a missing jwt secret fails at boot and names the property`() {
        runner.withPropertyValues("wasichai.security.jwt.secret=").run { context ->
            assertThat(context).hasFailed()
            assertThat(generateSequence(context.startupFailure) { it.cause }.map { it.message.orEmpty() }.joinToString(" | "))
                .contains("wasichai.security.jwt.secret must be at least 32 bytes")
        }
    }

    @Test
    fun `modules plug in through beans, and an app bean replaces the core default`() {
        val states =
            object : WorkflowStates {
                override suspend fun stateOf(
                    organizationId: UUID,
                    objectId: UUID
                ) = ObjectWorkflowState.NONE

                override suspend fun transitionNames(
                    organizationId: UUID,
                    objectId: UUID
                ) = setOf("approve")
            }
        runner
            .withBean(FieldTypeHandler::class.java, { shape })
            .withBean(WorkflowStates::class.java, { states })
            .withBean(SystemColumnContributor::class.java, { SystemColumnContributor { listOf(SystemColumn("workflow_state", "TEXT", "WORKFLOW")) } })
            .withBean("gisMigration", ModuleMigration::class.java, { ModuleMigration("gis", "classpath:db/wasichai/gis", ModuleMigration.MODULE_ORDER) })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(FieldTypeRegistry::class.java).types.last()).isEqualTo(FieldType("SHAPE"))
                assertThat(context.getBean(WorkflowStates::class.java)).isSameAs(states)
                assertThat(context.getBean(SystemColumns::class.java).names).contains("workflow_state")
                assertThat(context.getBeansOfType(ModuleMigration::class.java)).hasSize(2)
            }
    }

    @Test
    fun `an app ClusterLock wins over the default`() {
        val mine = ClusterLock(mock(DatabaseClient::class.java)) { error("not used") }
        runner.withBean(ClusterLock::class.java, { mine }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(ClusterLock::class.java)).isSameAs(mine)
        }
    }

    @Test
    fun `an app PasswordEncoder wins over the default`() {
        val encoder =
            object : PasswordEncoder {
                override fun encode(rawPassword: CharSequence?) = "custom"

                override fun matches(
                    rawPassword: CharSequence?,
                    encodedPassword: String?
                ) = true
            }
        runner.withBean(PasswordEncoder::class.java, { encoder }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(PasswordEncoder::class.java)).isSameAs(encoder)
        }
    }

    @Test
    fun `an unrelated app SecretKey bean does not become the jwt signing key`() {
        // some other encryption key, wrong algorithm, wrong bytes: it must never reach the jwt decoder
        val unrelated = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        val expected = SecretKeySpec("0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8), "HmacSHA256")
        runner.withBean(SecretKey::class.java, { unrelated }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(WasichaiJwtKey::class.java).key.encoded).isEqualTo(expected.encoded)
        }
    }

    @Test
    fun `the imports file registers all five core auto-configs, in dependency order`() {
        val candidates = ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates
        assertThat(candidates.filter { it.startsWith("wasichai.core.autoconfigure.") }).containsExactly(
            "wasichai.core.autoconfigure.WasichaiPlatformAutoConfiguration",
            "wasichai.core.autoconfigure.WasichaiSecurityAutoConfiguration",
            "wasichai.core.autoconfigure.WasichaiMetadataAutoConfiguration",
            "wasichai.core.autoconfigure.WasichaiDataAutoConfiguration",
            "wasichai.core.autoconfigure.WasichaiAdminAutoConfiguration"
        )
    }
}
