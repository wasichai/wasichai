package wasichai.agent.autoconfigure

import com.embabel.agent.core.AgentPlatform
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper
import wasichai.agent.AgentController
import wasichai.agent.AgentProperties
import wasichai.agent.AgentService
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

// the assistant. the tools only ever call services, so tenancy and permissions are the caller's.
// embabel finds WasichaiAgent by its @Agent annotation through a bean post-processor: a @Bean is enough.
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.agent", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AgentProperties::class)
class WasichaiAgentAutoConfiguration {
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
        tools: AgentTools
    ): WasichaiAgent = WasichaiAgent(properties, tools)

    // the platform is absent whenever EmbabelGate kept embabel out (no key, or switched off)
    @Bean
    @ConditionalOnMissingBean
    fun agentService(
        properties: AgentProperties,
        currentUser: CurrentUser,
        platform: ObjectProvider<AgentPlatform>
    ): AgentService = AgentService(properties, currentUser, platform)

    @Bean
    @ConditionalOnMissingBean
    fun agentController(agent: AgentService): AgentController = AgentController(agent)
}
