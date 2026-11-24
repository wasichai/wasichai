package wasichai.agent

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.platform.WasichaiSchemas
import wasichai.core.platform.bindNullable
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

// a conversation with the assistant, kept for the one user who started it (ADR-087)
data class AgentConversation(
    val id: UUID,
    val title: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

enum class AgentMessageRole { USER, ASSISTANT }

data class AgentMessage(
    val id: UUID,
    val role: AgentMessageRole,
    val content: String,
    val steps: List<AgentStep> = emptyList(),
    val truncated: Boolean = false,
    val usage: AgentUsage? = null,
    val model: String? = null,
    val createdAt: Instant
)

data class AgentConversationDetail(
    val conversation: AgentConversation,
    val messages: List<AgentMessage>
)

// one exchange: what was asked and what came back, both as stored
data class AgentExchange(
    val question: AgentMessage,
    val answer: AgentMessage
)

private const val TITLE_LENGTH = 80
private const val LIST_LIMIT = 100
private const val QUESTION_LENGTH = 8_000

class AgentConversationRepository(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val json: ObjectMapper
) {
    private val conversations get() = "${schemas.metadata}.agent_conversations"
    private val messages get() = "${schemas.metadata}.agent_messages"

    suspend fun create(
        user: AuthenticatedUser,
        title: String?
    ): AgentConversation =
        db
            .sql("INSERT INTO $conversations (organization_id, user_id, title) VALUES (:org, :user, :title) RETURNING id, title, created_at, updated_at")
            .bind("org", user.organizationId)
            .bind("user", user.userId)
            .bindNullable("title", title)
            .map { row, _ -> conversation(row) }
            .one()
            .awaitFirst()

    suspend fun list(user: AuthenticatedUser): List<AgentConversation> =
        db
            .sql(
                "SELECT id, title, created_at, updated_at FROM $conversations " +
                    "WHERE organization_id = :org AND user_id = :user AND archived_at IS NULL ORDER BY updated_at DESC LIMIT $LIST_LIMIT"
            ).bind("org", user.organizationId)
            .bind("user", user.userId)
            .map { row, _ -> conversation(row) }
            .all()
            .collectList()
            .awaitSingle()

    // owner only: someone else's conversation, or an archived one, does not exist
    suspend fun find(
        user: AuthenticatedUser,
        id: UUID
    ): AgentConversation? =
        db
            .sql(
                "SELECT id, title, created_at, updated_at FROM $conversations " +
                    "WHERE id = :id AND organization_id = :org AND user_id = :user AND archived_at IS NULL"
            ).bind("id", id)
            .bind("org", user.organizationId)
            .bind("user", user.userId)
            .map { row, _ -> conversation(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun rename(
        user: AuthenticatedUser,
        id: UUID,
        title: String?
    ): Boolean = update(user, id, "title = :title, updated_at = now()") { it.bindNullable("title", title) }

    suspend fun archive(
        user: AuthenticatedUser,
        id: UUID
    ): Boolean = update(user, id, "archived_at = now()") { it }

    // a new answer moves the conversation to the top; the first one names it, when nobody did
    suspend fun touch(
        user: AuthenticatedUser,
        id: UUID,
        title: String
    ) {
        update(user, id, "updated_at = now(), title = COALESCE(title, :title)") { it.bind("title", title) }
    }

    private suspend fun update(
        user: AuthenticatedUser,
        id: UUID,
        set: String,
        binds: (DatabaseClient.GenericExecuteSpec) -> DatabaseClient.GenericExecuteSpec
    ): Boolean {
        val spec =
            db
                .sql("UPDATE $conversations SET $set WHERE id = :id AND organization_id = :org AND user_id = :user AND archived_at IS NULL")
                .bind("id", id)
                .bind("org", user.organizationId)
                .bind("user", user.userId)
        return (binds(spec).fetch().rowsUpdated().awaitSingleOrNull() ?: 0) > 0
    }

    suspend fun messages(
        user: AuthenticatedUser,
        conversationId: UUID
    ): List<AgentMessage> =
        db
            .sql("SELECT $MESSAGE_COLUMNS FROM $messages WHERE conversation_id = :id AND organization_id = :org ORDER BY created_at, id")
            .bind("id", conversationId)
            .bind("org", user.organizationId)
            .map { row, _ -> message(row) }
            .all()
            .collectList()
            .awaitSingle()

    // the last [limit] answered exchanges, oldest first. a run that ran out of room taught nothing: left out.
    suspend fun recentTurns(
        user: AuthenticatedUser,
        conversationId: UUID,
        limit: Int
    ): List<AgentTurn> {
        if (limit <= 0) return emptyList()
        val recent =
            db
                .sql(
                    "SELECT $MESSAGE_COLUMNS FROM $messages WHERE conversation_id = :id AND organization_id = :org " +
                        "ORDER BY created_at DESC, id DESC LIMIT :limit"
                ).bind("id", conversationId)
                .bind("org", user.organizationId)
                .bind("limit", limit * 2)
                .map { row, _ -> message(row) }
                .all()
                .collectList()
                .awaitSingle()
                .reversed()
        return recent
            .zipWithNext()
            .filter { (q, a) -> q.role == AgentMessageRole.USER && a.role == AgentMessageRole.ASSISTANT && !a.truncated }
            .map { (q, a) -> AgentTurn(q.content, a.content) }
            .takeLast(limit)
    }

    suspend fun append(
        user: AuthenticatedUser,
        conversationId: UUID,
        role: AgentMessageRole,
        content: String,
        answer: AgentAnswer? = null
    ): AgentMessage =
        db
            .sql(
                "INSERT INTO $messages (conversation_id, organization_id, role, content, steps, usage, model, truncated) " +
                    "VALUES (:conversation, :org, :role, :content, CAST(:steps AS jsonb), CAST(:usage AS jsonb), :model, :truncated) " +
                    "RETURNING $MESSAGE_COLUMNS"
            ).bind("conversation", conversationId)
            .bind("org", user.organizationId)
            .bind("role", role.name)
            .bind("content", content)
            .bindNullable("steps", answer?.let { json.writeValueAsString(it.steps) })
            .bindNullable("usage", answer?.usage?.let { json.writeValueAsString(it) })
            .bindNullable("model", answer?.model)
            .bind("truncated", answer?.truncated ?: false)
            .map { row, _ -> message(row) }
            .one()
            .awaitFirst()

    private fun conversation(row: Readable) =
        AgentConversation(
            id = row.get("id", UUID::class.java)!!,
            title = row.get("title", String::class.java),
            createdAt = instant(row, "created_at"),
            updatedAt = instant(row, "updated_at")
        )

    private fun message(row: Readable) =
        AgentMessage(
            id = row.get("id", UUID::class.java)!!,
            role = AgentMessageRole.valueOf(row.get("role", String::class.java)!!),
            content = row.get("content", String::class.java)!!,
            steps = row.get("steps_json", String::class.java)?.let { json.readValue(it, STEPS) }.orEmpty(),
            truncated = row.get("truncated", java.lang.Boolean::class.java)?.booleanValue() ?: false,
            usage = row.get("usage_json", String::class.java)?.let { json.readValue(it, AgentUsage::class.java) },
            model = row.get("model", String::class.java),
            createdAt = instant(row, "created_at")
        )

    private fun instant(
        row: Readable,
        column: String
    ): Instant = row.get(column, OffsetDateTime::class.java)!!.toInstant()

    private companion object {
        const val MESSAGE_COLUMNS = "id, role, content, steps::text AS steps_json, usage::text AS usage_json, model, truncated, created_at"
        val STEPS = object : TypeReference<List<AgentStep>>() {}
    }
}

/**
 * The assistant with a memory (ADR-087). Each conversation is its owner's alone: every read and
 * write is scoped to the caller's organization and user, so someone else's id answers 404. A new
 * question is asked with the earlier exchanges, text only, and both sides are kept once the run
 * answered; a run that failed keeps nothing, so a retry does not repeat the question.
 */
class AgentConversationService(
    private val repository: AgentConversationRepository,
    private val agent: AgentService,
    private val properties: AgentProperties,
    private val currentUser: CurrentUser
) {
    suspend fun list(): List<AgentConversation> = repository.list(currentUser.require())

    suspend fun create(title: String?): AgentConversation = repository.create(currentUser.require(), cleanTitle(title))

    suspend fun get(id: UUID): AgentConversationDetail {
        val user = currentUser.require()
        val conversation = repository.find(user, id) ?: throw missing(id)
        return AgentConversationDetail(conversation, repository.messages(user, id))
    }

    suspend fun rename(
        id: UUID,
        title: String?
    ): AgentConversation {
        val user = currentUser.require()
        if (!repository.rename(user, id, cleanTitle(title))) throw missing(id)
        return repository.find(user, id) ?: throw missing(id)
    }

    suspend fun archive(id: UUID) {
        if (!repository.archive(currentUser.require(), id)) throw missing(id)
    }

    suspend fun send(
        id: UUID,
        question: String?
    ): AgentExchange {
        val user = currentUser.require()
        repository.find(user, id) ?: throw missing(id)
        val text = question?.trim().orEmpty()
        if (text.length > QUESTION_LENGTH) throw ValidationException("Question too long", "question", "must be at most $QUESTION_LENGTH characters")
        val history = repository.recentTurns(user, id, properties.historyCap)
        // permission, policy, model and blank-question checks are the plain ask's
        val answer = agent.ask(text, history)
        val asked = repository.append(user, id, AgentMessageRole.USER, text)
        val answered = repository.append(user, id, AgentMessageRole.ASSISTANT, answer.answer, answer)
        repository.touch(user, id, titleOf(text))
        return AgentExchange(asked, answered)
    }

    private fun missing(id: UUID) = NotFoundException("Conversation $id not found")

    private fun cleanTitle(title: String?): String? = title?.trim()?.ifEmpty { null }?.take(TITLE_LENGTH)

    // the first question names the conversation: one line, cut at a word
    private fun titleOf(question: String): String {
        val line = question.lineSequence().first().trim()
        if (line.length <= TITLE_LENGTH) return line
        val cut = line.take(TITLE_LENGTH)
        return cut.substringBeforeLast(' ', cut).trimEnd() + "…"
    }
}
