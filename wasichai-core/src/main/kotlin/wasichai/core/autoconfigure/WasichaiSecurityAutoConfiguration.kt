package wasichai.core.autoconfigure

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.codec.ServerCodecConfigurer
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
import wasichai.core.common.GlobalExceptionHandler
import wasichai.core.data.IdempotencyKeys
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthConfigController
import wasichai.core.identity.AuthController
import wasichai.core.identity.AuthService
import wasichai.core.identity.ConfiguredPasswordPolicy
import wasichai.core.identity.CurrentUser
import wasichai.core.identity.InMemoryLoginAttemptStore
import wasichai.core.identity.JwtService
import wasichai.core.identity.LoginAttemptStore
import wasichai.core.identity.LoginThrottle
import wasichai.core.identity.MyOrgUnitsController
import wasichai.core.identity.OrgUnitDirectory
import wasichai.core.identity.PasswordPolicy
import wasichai.core.identity.RevocationCheckingJwtDecoder
import wasichai.core.identity.RoleDirectory
import wasichai.core.identity.RoleQueries
import wasichai.core.identity.ServiceAccountTokenController
import wasichai.core.identity.ServiceAccountTokenService
import wasichai.core.identity.TokenRevocation
import wasichai.core.identity.UserDirectory
import wasichai.core.identity.UserPreferencesController
import wasichai.core.identity.UserPreferencesRepository
import wasichai.core.identity.UserPreferencesService
import wasichai.core.identity.UserRepository
import wasichai.core.identity.WasichaiJwtKey
import wasichai.core.identity.oidc.DeniedRealmAccess
import wasichai.core.identity.oidc.IssuerRoutingJwtDecoder
import wasichai.core.identity.oidc.RealmJwtDecoder
import wasichai.core.identity.oidc.RealmPrincipalConverter
import wasichai.core.identity.oidc.RealmUsers
import wasichai.core.identity.oidc.realmJwtDecoder
import wasichai.core.platform.JwtProperties
import wasichai.core.platform.WasichaiIdentityProperties
import wasichai.core.platform.WasichaiLoginProperties
import wasichai.core.platform.WasichaiOrganizationsProperties
import wasichai.core.platform.WasichaiPasswordProperties
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

    // with wasichai.security.jwt.revocation, a token issued before its user's marker is refused (ADR-059).
    // an app's own decoder bean replaces this one, the check included.
    // oidc: the same decoder serves the app's own tokens (service accounts), the realm's go to the realm decoder
    @Bean
    @ConditionalOnMissingBean
    fun jwtDecoder(
        wasichaiJwtKey: WasichaiJwtKey,
        properties: JwtProperties,
        tokenRevocation: TokenRevocation,
        identity: WasichaiIdentityProperties,
        realm: ObjectProvider<RealmJwtDecoder>
    ): ReactiveJwtDecoder {
        val decoder = NimbusReactiveJwtDecoder.withSecretKey(wasichaiJwtKey.key).macAlgorithm(MacAlgorithm.HS256).build()
        val local = if (properties.revocation) RevocationCheckingJwtDecoder(decoder, tokenRevocation) else decoder
        if (!identity.isOidc) return local
        return IssuerRoutingJwtDecoder(realm.getObject(), local, properties.issuer)
    }

    // only in oidc. the converter takes its issuer from the realm decoder: one string, so a token the
    // decoder accepted as the realm's is always the one the converter translates
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "wasichai.security", name = ["identity"], havingValue = "oidc")
    class OidcConfiguration {
        @Bean
        @ConditionalOnMissingBean
        fun realmJwtDecoder(identity: WasichaiIdentityProperties): RealmJwtDecoder = realmJwtDecoder(identity.oidc)

        @Bean
        @ConditionalOnMissingBean
        fun realmUsers(
            db: DatabaseClient,
            schemas: WasichaiSchemas,
            identity: WasichaiIdentityProperties
        ): RealmUsers = RealmUsers(db, schemas, identity.oidc.rolesCache)

        @Bean
        @ConditionalOnMissingBean
        fun realmPrincipalConverter(
            realm: RealmJwtDecoder,
            users: RealmUsers
        ): RealmPrincipalConverter = RealmPrincipalConverter(realm.issuer, users)

        @Bean
        @ConditionalOnMissingBean
        fun deniedRealmAccess(
            errors: GlobalExceptionHandler,
            codecs: ServerCodecConfigurer
        ): DeniedRealmAccess = DeniedRealmAccess(errors, codecs)
    }

    // the marker is written whatever the switch; read only with it on
    @Bean
    @ConditionalOnMissingBean
    fun tokenRevocation(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        properties: JwtProperties
    ): TokenRevocation = TokenRevocation(db, schemas, properties.revocation, properties.revocationCache)

    // in memory: one node's count. a cluster declares its own store (ADR-059)
    @Bean
    @ConditionalOnMissingBean
    fun loginAttemptStore(): LoginAttemptStore = InMemoryLoginAttemptStore()

    @Bean
    @ConditionalOnMissingBean
    fun loginThrottle(
        properties: WasichaiLoginProperties,
        store: LoginAttemptStore
    ): LoginThrottle = LoginThrottle(properties, store)

    @Bean
    @ConditionalOnMissingBean
    fun passwordPolicy(properties: WasichaiPasswordProperties): PasswordPolicy = ConfiguredPasswordPolicy(properties)

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
    // oidc: realm tokens become the app's user (realmPrincipalConverter); one let in without access is
    // refused here, before any endpoint (deniedRealmAccess)
    @Bean
    @ConditionalOnMissingBean
    @Order(0)
    fun securityFilterChain(
        http: ServerHttpSecurity,
        jwtDecoder: ReactiveJwtDecoder,
        realmPrincipalConverter: ObjectProvider<RealmPrincipalConverter>,
        deniedRealmAccess: ObjectProvider<DeniedRealmAccess>
    ): SecurityWebFilterChain {
        val converter = realmPrincipalConverter.ifAvailable
        val denied = deniedRealmAccess.ifAvailable
        return http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .cors { }
            .authorizeExchange {
                it.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // /api/auth/token: service accounts' client credentials (ADR-043). /api/auth/config: how to sign in
                it
                    .pathMatchers("/api/auth/login", "/api/auth/token", "/api/auth/config", "/api/health", "/actuator/health/**")
                    .permitAll()
                if (denied == null) it.anyExchange().authenticated() else it.anyExchange().access(denied.authorization)
            }.oauth2ResourceServer { server ->
                server.jwt { jwt ->
                    jwt.jwtDecoder(jwtDecoder)
                    converter?.let { jwt.jwtAuthenticationConverter(it) }
                }
                denied?.let { server.accessDeniedHandler(it.handler) }
            }.build()
    }

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
    fun accessPolicy(roleQueries: RoleQueries): AccessPolicy = AccessPolicy(roleQueries)

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
        jwtService: JwtService,
        throttle: LoginThrottle,
        revocation: TokenRevocation
    ): AuthService = AuthService(users, roleQueries, passwordEncoder, jwtService, throttle, revocation)

    @Bean
    @ConditionalOnMissingBean
    fun authController(
        authService: AuthService,
        currentUser: CurrentUser,
        identity: WasichaiIdentityProperties
    ): AuthController = AuthController(authService, currentUser, identity.isOidc)

    @Bean
    @ConditionalOnMissingBean
    fun authConfigController(identity: WasichaiIdentityProperties): AuthConfigController = AuthConfigController(identity)

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
        jwtService: JwtService,
        throttle: LoginThrottle
    ): ServiceAccountTokenService = ServiceAccountTokenService(db, schemas, roleQueries, passwordEncoder, jwtService, throttle)

    @Bean
    @ConditionalOnMissingBean
    fun serviceAccountTokenController(tokens: ServiceAccountTokenService): ServiceAccountTokenController = ServiceAccountTokenController(tokens)
}
