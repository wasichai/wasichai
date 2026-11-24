package wasichai.agent.autoconfigure

import com.embabel.agent.core.AgentPlatform
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.agent.AgentAccessPolicy
import wasichai.agent.AgentAnswerFilter
import wasichai.agent.AgentController
import wasichai.agent.AgentConversationController
import wasichai.agent.AgentConversationRepository
import wasichai.agent.AgentConversationService
import wasichai.agent.AgentInstructions
import wasichai.agent.AgentProperties
import wasichai.agent.AgentResultFilter
import wasichai.agent.AgentRunListener
import wasichai.agent.AgentService
import wasichai.agent.AgentSettingsRepository
import wasichai.agent.AgentSettingsService
import wasichai.agent.AgentToolContributor
import wasichai.agent.AgentTools
import wasichai.agent.NoRecordTransitions
import wasichai.agent.RecordTransitions
import wasichai.agent.WasichaiAgent
import wasichai.core.audit.AuditQueryService
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.data.RecordQueryParser
import wasichai.core.data.RecordService
import wasichai.core.data.RelatedRecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataMapper
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.RelationshipService
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas

// the assistant. the tools only ever call services, so tenancy and permissions are the caller's.
// embabel finds WasichaiAgent by its @Agent annotation through a bean post-processor: a @Bean is enough.
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.agent", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AgentProperties::class)
class WasichaiAgentAutoConfiguration {
    // conversations and model picks (ADR-087). never @ConditionalOnMissingBean: core's own ModuleMigration would make it back off
    @Bean
    fun wasichaiAgentMigration(): ModuleMigration = ModuleMigration("agent", "classpath:db/wasichai/agent", ModuleMigration.MODULE_ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun agentTools(
        metadata: MetadataService,
        mapper: MetadataMapper,
        records: RecordService,
        relationships: RelationshipService,
        related: RelatedRecordService,
        transitions: ObjectProvider<RecordTransitions>,
        queries: RecordQueryParser,
        audit: AuditQueryService,
        currentUser: CurrentUser,
        json: JsonMapper
    ): AgentTools =
        AgentTools(
            metadata,
            mapper,
            records,
            relationships,
            related,
            transitions.getIfAvailable { NoRecordTransitions() },
            queries,
            audit,
            currentUser,
            json
        )

    @Bean
    @ConditionalOnMissingBean
    fun wasichaiAgent(
        properties: AgentProperties,
        tools: AgentTools,
        contributors: ObjectProvider<AgentToolContributor>,
        json: JsonMapper
    ): WasichaiAgent = WasichaiAgent(properties, tools, contributors.orderedStream().toList(), json)

    // everyone may ask, as before. an app replaces it to switch the assistant on per organization.
    @Bean
    @ConditionalOnMissingBean
    fun agentAccessPolicy(): AgentAccessPolicy = AgentAccessPolicy.ALLOW_ALL

    // the platform is absent whenever EmbabelGate kept embabel out (no key, or switched off).
    // filters and listeners: every bean the app declares, in @Order; none is the old behaviour.
    @Bean
    @ConditionalOnMissingBean
    fun agentService(
        properties: AgentProperties,
        currentUser: CurrentUser,
        platform: ObjectProvider<AgentPlatform>,
        policy: AgentAccessPolicy,
        resultFilters: ObjectProvider<AgentResultFilter>,
        answerFilters: ObjectProvider<AgentAnswerFilter>,
        listeners: ObjectProvider<AgentRunListener>,
        instructions: ObjectProvider<AgentInstructions>,
        settings: AgentSettingsService
    ): AgentService =
        AgentService(
            properties,
            currentUser,
            platform,
            policy,
            resultFilters.orderedStream().toList(),
            answerFilters.orderedStream().toList(),
            listeners.orderedStream().toList(),
            instructions.orderedStream().toList(),
            settings
        )

    @Bean
    @ConditionalOnMissingBean
    fun agentController(agent: AgentService): AgentController = AgentController(agent)

    @Bean
    @ConditionalOnMissingBean
    fun agentSettingsService(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        properties: AgentProperties
    ): AgentSettingsService = AgentSettingsService(AgentSettingsRepository(db, schemas), properties)

    @Bean
    @ConditionalOnMissingBean
    fun agentConversationService(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        json: JsonMapper,
        agent: AgentService,
        properties: AgentProperties,
        currentUser: CurrentUser
    ): AgentConversationService = AgentConversationService(AgentConversationRepository(db, schemas, json), agent, properties, currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun agentConversationController(
        conversations: AgentConversationService,
        settings: AgentSettingsService,
        currentUser: CurrentUser
    ): AgentConversationController = AgentConversationController(conversations, settings, currentUser)
}
