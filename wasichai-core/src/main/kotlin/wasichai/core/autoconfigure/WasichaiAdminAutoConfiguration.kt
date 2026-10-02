package wasichai.core.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.crypto.password.PasswordEncoder
import wasichai.core.admin.AdminService
import wasichai.core.admin.RoleAdminController
import wasichai.core.admin.UserAdminController
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectActionRepository
import wasichai.core.metadata.ObjectSchemaManager
import wasichai.core.organization.OrganizationController
import wasichai.core.organization.OrganizationRepository
import wasichai.core.organization.OrganizationService
import wasichai.core.platform.WasichaiSchemas

// users, roles, permissions and the tenant itself
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class])
class WasichaiAdminAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun adminService(
        db: DatabaseClient,
        metadata: MetadataService,
        actions: ObjectActionRepository,
        passwordEncoder: PasswordEncoder,
        currentUser: CurrentUser,
        schemas: WasichaiSchemas
    ): AdminService = AdminService(db, metadata, actions, passwordEncoder, currentUser, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun userAdminController(admin: AdminService): UserAdminController = UserAdminController(admin)

    @Bean
    @ConditionalOnMissingBean
    fun roleAdminController(admin: AdminService): RoleAdminController = RoleAdminController(admin)

    @Bean
    @ConditionalOnMissingBean
    fun organizationRepository(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): OrganizationRepository = OrganizationRepository(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun organizationService(
        organizations: OrganizationRepository,
        objects: CustomObjectRepository,
        schema: ObjectSchemaManager,
        passwordEncoder: PasswordEncoder,
        currentUser: CurrentUser,
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): OrganizationService = OrganizationService(organizations, objects, schema, passwordEncoder, currentUser, db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun organizationController(organizations: OrganizationService): OrganizationController = OrganizationController(organizations)
}
