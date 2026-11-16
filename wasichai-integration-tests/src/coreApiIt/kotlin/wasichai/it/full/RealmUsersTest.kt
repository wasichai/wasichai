package wasichai.it.full

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.identity.oidc.RealmUsers
import wasichai.core.platform.WasichaiSchemas
import java.time.Duration
import java.util.UUID

// the sql behind a realm sign-in (oidc mode), against the real schema
class RealmUsersTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val users by lazy { RealmUsers(db, schemas, Duration.ZERO) }

    private class Row(
        val email: String,
        val displayName: String,
        val subject: String?
    )

    // the database is shared and kept between runs: every test its own address
    private fun address(prefix: String) = "${uniqueName(prefix)}@perene.gob.pe"

    private suspend fun organization(): UUID {
        val slug = uniqueName("realm").lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val id = UUID.randomUUID()
        db
            .sql("INSERT INTO ${schemas.metadata}.organizations (id, name, slug) VALUES (:id, :name, :slug)")
            .bind("id", id)
            .bind("name", slug)
            .bind("slug", slug)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
        return id
    }

    private suspend fun invite(
        organizationId: UUID,
        email: String,
        subject: String? = null
    ): UUID {
        val id = UUID.randomUUID()
        val insert =
            db
                .sql(
                    "INSERT INTO ${schemas.metadata}.users (id, organization_id, email, password_hash, display_name, external_subject) " +
                        "VALUES (:id, :organizationId, :email, NULL, 'Invited', :subject)"
                ).bind("id", id)
                .bind("organizationId", organizationId)
                .bind("email", email)
        (if (subject == null) insert.bindNull("subject", String::class.java) else insert.bind("subject", subject))
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
        return id
    }

    private suspend fun row(id: UUID): Row =
        db
            .sql("SELECT email, display_name, external_subject FROM ${schemas.metadata}.users WHERE id = :id")
            .bind("id", id)
            .map {
                r,
                _
                ->
                Row(r.get("email", String::class.java)!!, r.get("display_name", String::class.java)!!, r.get("external_subject", String::class.java))
            }.one()
            .awaitFirstOrNull()!!

    @Test
    fun `the first sign-in with a verified email links the row`(): Unit =
        runBlocking {
            val org = organization()
            val email = address("ana")
            val id = invite(org, email)
            val subject = UUID.randomUUID().toString()

            // keycloak may write the address in another case: the match ignores it
            val user = users.resolve(org, subject, email.uppercase(), true, "Ana Quispe")!!

            assertThat(user.id).isEqualTo(id)
            assertThat(user.organizationId).isEqualTo(org)
            assertThat(user.enabled).isTrue()
            assertThat(row(id).subject).isEqualTo(subject)
            assertThat(row(id).displayName).isEqualTo("Ana Quispe")
            assertThat(users.organizationIdBySlug("no-such-${UUID.randomUUID()}")).isNull()
        }

    @Test
    fun `an unverified email links nothing`(): Unit =
        runBlocking {
            val org = organization()
            val email = address("ana")
            val id = invite(org, email)

            val user = users.resolve(org, UUID.randomUUID().toString(), email, false, "Someone Else")

            assertThat(user).isNull()
            val untouched = row(id)
            assertThat(untouched.subject).isNull()
            assertThat(untouched.displayName).isEqualTo("Invited")
        }

    @Test
    fun `a linked row is found by subject even after its email changed`(): Unit =
        runBlocking {
            val org = organization()
            val subject = UUID.randomUUID().toString()
            val linked = invite(org, address("ana"), subject)
            val changed = address("ana.q")
            // an unlinked row at the new address (another case) waits for its own person: never a second link
            val other = invite(org, changed.uppercase())

            val user = users.resolve(org, subject, changed, true, null)!!

            assertThat(user.id).isEqualTo(linked)
            assertThat(user.email).isEqualTo(changed)
            assertThat(row(linked).email).isEqualTo(changed)
            // no name in the token: the row keeps its own
            assertThat(row(linked).displayName).isEqualTo("Invited")
            assertThat(row(other).subject).isNull()
        }

    @Test
    fun `the same email in another organization is never linked`(): Unit =
        runBlocking {
            val perene = organization()
            val satipo = organization()
            val email = address("ana")
            val inSatipo = invite(satipo, email)

            val user = users.resolve(perene, UUID.randomUUID().toString(), email, true, "Ana")

            assertThat(user).isNull()
            assertThat(row(inSatipo).subject).isNull()
        }

    @Test
    fun `a role removed shows after the cache`(): Unit =
        runBlocking {
            val org = organization()
            val id = invite(org, address("ana"), UUID.randomUUID().toString())
            val role = UUID.randomUUID()
            db
                .sql("INSERT INTO ${schemas.metadata}.roles (id, organization_id, name, label) VALUES (:id, :org, 'CAJERO', 'Cajero')")
                .bind("id", role)
                .bind("org", org)
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()
            db
                .sql("INSERT INTO ${schemas.metadata}.user_roles (user_id, role_id) VALUES (:user, :role)")
                .bind("user", id)
                .bind("role", role)
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()

            assertThat(users.roles(id)).containsExactly("CAJERO")

            db
                .sql("DELETE FROM ${schemas.metadata}.user_roles WHERE user_id = :user")
                .bind("user", id)
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()

            // rolesCache = 0: the next read is the database's
            assertThat(users.roles(id)).isEmpty()
        }
}
