package wasichai.core.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
import wasichai.core.audit.AuditPage
import wasichai.core.data.IdempotencyKeys
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthController
import wasichai.core.identity.AuthService
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.JwtService
import wasichai.core.identity.MyOrgUnitsController
import wasichai.core.identity.OrgUnitDirectory
import wasichai.core.identity.RoleDirectory
import wasichai.core.identity.RoleQueries
import wasichai.core.identity.ServiceAccountTokenController
import wasichai.core.identity.ServiceAccountTokenService
import wasichai.core.identity.UserDirectory
import wasichai.core.identity.UserPreferencesController
import wasichai.core.identity.UserPreferencesRepository
import wasichai.core.identity.UserPreferencesService
import wasichai.core.identity.UserRepository
import wasichai.core.identity.WasichaiJwtKey
import wasichai.core.platform.JwtProperties
import wasichai.core.platform.WasichaiOrganizationsProperties
import wasichai.core.platform.WasichaiSchemas
import wasichai.core.platform.WasichaiWebProperties
import javax.crypto.spec.SecretKeySpec

// own HS256 jwt (ADR-010) and the identity beans. runs before boot's security defaults, so they
// see our decoder and back off instead of generating an in-memory user.
@AutoConfiguration(
    after = [WasichaiPlatformAutoConfiguration::class],
    beforeName = [
        "org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.reactive.ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration"
    ]
)
@EnableWebFluxSecurity
class WasichaiSecurityAutoConfiguration {
    // wrapped in WasichaiJwtKey, not a plain SecretKey: an unrelated app SecretKey bean (some other
    // encryption key, say) would otherwise silently become the signing key, or make injection
    // ambiguous. a dedicated type can only mean one thing.
    @Bean
    @ConditionalOnMissingBean(WasichaiJwtKey::class)
    fun wasichaiJwtKey(properties: JwtProperties): WasichaiJwtKey {
        val bytes = properties.secret.toByteArray(Charsets.UTF_8)
        // HS256 needs >= 256 bits. fail at boot, not at first login.
        require(bytes.size >= 32) { "wasichai.security.jwt.secret must be at least 32 bytes (env WASICHAI_JWT_SECRET)" }
        return WasichaiJwtKey(SecretKeySpec(bytes, "HmacSHA256"))
    }

    @Bean
    @ConditionalOnMissingBean
    fun jwtDecoder(wasichaiJwtKey: WasichaiJwtKey): ReactiveJwtDecoder =
        NimbusReactiveJwtDecoder.withSecretKey(wasichaiJwtKey.key).macAlgorithm(MacAlgorithm.HS256).build()

    @Bean
    @ConditionalOnMissingBean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    // ordered first: boot's ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration contributes its
    // own catch-all SecurityWebFilterChain whenever a ReactiveJwtDecoder bean exists (its
    // @ConditionalOnDefaultWebSecurity checks for a SERVLET SecurityFilterChain, which a pure
    // WebFlux app never has, so it never backs off here). both chains match anyExchange(), and the
    // reactive security infra runs the first whose matcher matches and stops - without an explicit
    // order that pick is undefined. @Order(0) pins ours first, so the public paths and CORS below
    // always apply; boot's redundant chain is registered but never reached.
    @Bean
    @ConditionalOnMissingBean
    @Order(0)
    fun securityFilterChain(
        http: ServerHttpSecurity,
        jwtDecoder: ReactiveJwtDecoder
    ): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .cors { }
            .authorizeExchange {
                it.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // /api/auth/token: service accounts' client credentials (ADR-043)
                it.pathMatchers("/api/auth/login", "/api/auth/token", "/api/health", "/actuator/health/**").permitAll()
                it.anyExchange().authenticated()
            }.oauth2ResourceServer { server -> server.jwt { it.jwtDecoder(jwtDecoder) } }
            .build()

    @Bean
    @ConditionalOnMissingBean
    fun corsConfigurationSource(web: WasichaiWebProperties): CorsConfigurationSource {
        val config =
            CorsConfiguration().apply {
                allowedOriginPatterns = web.corsAllowedOriginPatterns
                allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                allowedHeaders = listOf("*")
                // a browser on another origin reads only what is exposed: a record's version to send back as
                // If-Match (ADR-051), the audit list's next page (ADR-052), a replayed create and when to retry (ADR-058)
                exposedHeaders = listOf(HttpHeaders.ETAG, AuditPage.NEXT_CURSOR_HEADER, IdempotencyKeys.REPLAYED, HttpHeaders.RETRY_AFTER)
                allowCredentials = true
            }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    @Bean
    @ConditionalOnMissingBean
    fun roleQueries(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): RoleQueries = RoleQueries(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun roleDirectory(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): RoleDirectory = RoleDirectory(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun orgUnitDirectory(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): OrgUnitDirectory = OrgUnitDirectory(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun userDirectory(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): UserDirectory = UserDirectory(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun currentUser(
        roleQueries: RoleQueries,
        organizations: WasichaiOrganizationsProperties
    ): CurrentUser = CurrentUser(roleQueries, organizations.separateProvisioning)

    @Bean
    @ConditionalOnMissingBean
    fun accessPolicy(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): AccessPolicy = AccessPolicy(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun userRepository(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): UserRepository = UserRepository(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun jwtService(
        properties: JwtProperties,
        wasichaiJwtKey: WasichaiJwtKey
    ): JwtService = JwtService(properties, wasichaiJwtKey)

    @Bean
    @ConditionalOnMissingBean
    fun authService(
        users: UserRepository,
        roleQueries: RoleQueries,
        passwordEncoder: PasswordEncoder,
        jwtService: JwtService
    ): AuthService = AuthService(users, roleQueries, passwordEncoder, jwtService)

    @Bean
    @ConditionalOnMissingBean
    fun authController(
        authService: AuthService,
        currentUser: CurrentUser
    ): AuthController = AuthController(authService, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun userPreferencesRepository(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): UserPreferencesRepository = UserPreferencesRepository(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun userPreferencesService(repository: UserPreferencesRepository): UserPreferencesService = UserPreferencesService(repository)

    @Bean
    @ConditionalOnMissingBean
    fun userPreferencesController(
        service: UserPreferencesService,
        currentUser: CurrentUser
    ): UserPreferencesController = UserPreferencesController(service, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun myOrgUnitsController(
        units: OrgUnitDirectory,
        currentUser: CurrentUser
    ): MyOrgUnitsController = MyOrgUnitsController(units, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun serviceAccountTokenService(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        roleQueries: RoleQueries,
        passwordEncoder: PasswordEncoder,
        jwtService: JwtService
    ): ServiceAccountTokenService = ServiceAccountTokenService(db, schemas, roleQueries, passwordEncoder, jwtService)

    @Bean
    @ConditionalOnMissingBean
    fun serviceAccountTokenController(tokens: ServiceAccountTokenService): ServiceAccountTokenController = ServiceAccountTokenController(tokens)
}
