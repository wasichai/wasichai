package wasichai.it.full

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import wasichai.core.identity.AdminAudit
import wasichai.core.identity.ServiceAccountSecrets
import wasichai.core.identity.oidc.RealmAccountResolution
import wasichai.core.identity.oidc.RealmServiceAccounts
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.RealmServiceAccount
import wasichai.core.platform.WasichaiSchemas
import java.time.Duration
import java.util.UUID

// the sql behind a realm client's token (oidc mode), against the real schema
class RealmServiceAccountsTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var lock: ClusterLock

    @Autowired
    private lateinit var audit: AdminAudit

    @Autowired
    private lateinit var transactionManager: ReactiveTransactionManager

    private val clientId = "carga-perene"

    private fun accounts(vararg configured: RealmServiceAccount): RealmServiceAccounts =
        RealmServiceAccounts(
            db,
            schemas,
            configured.associateBy { it.clientId },
            lock,
            audit,
            { TransactionalOperator.create(transactionManager) },
            Duration.ZERO
        )

    private val carga by lazy { accounts(RealmServiceAccount(clientId, listOf("CARGA_DATOS"))) }

    private class AccountRow(
        val id: UUID,
        val name: String,
        val externalClientId: String?,
        val secretHash: String?,
        val enabled: Boolean
    )

    private class BackingRow(
        val email: String,
        val passwordHash: String?,
        val displayName: String,
        val enabled: Boolean
    )

    private suspend fun organization(vararg roles: String): UUID {
        val slug = uniqueName("realm-sa").lowercase().replace(Regex("[^a-z0-9-]"), "-")
        val id = UUID.randomUUID()
        db
            .sql("INSERT INTO ${schemas.metadata}.organizations (id, name, slug) VALUES (:id, :name, :slug)")
            .bind("id", id)
            .bind("name", slug)
            .bind("slug", slug)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        roles.forEach { role ->
            db
                .sql("INSERT INTO ${schemas.metadata}.roles (id, organization_id, name, label) VALUES (:id, :org, :name, :name)")
                .bind("id", UUID.randomUUID())
                .bind("org", id)
                .bind("name", role)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        return id
    }

    private suspend fun accountRows(organizationId: UUID): List<AccountRow> =
        db
            .sql(
                "SELECT id, name, external_client_id, secret_hash, enabled FROM ${schemas.metadata}.service_accounts " +
                    "WHERE organization_id = :org ORDER BY created_at"
            ).bind("org", organizationId)
            .map { r, _ ->
                AccountRow(
                    r.get("id", UUID::class.java)!!,
                    r.get("name", String::class.java)!!,
                    r.get("external_client_id", String::class.java),
                    r.get("secret_hash", String::class.java),
                    r.get("enabled", java.lang.Boolean::class.java)!!.booleanValue()
                )
            }.all()
            .asFlow()
            .toList()

    private suspend fun backing(id: UUID): BackingRow =
        db
            .sql("SELECT email, password_hash, display_name, enabled FROM ${schemas.metadata}.users WHERE id = :id")
            .bind("id", id)
            .map { r, _ ->
                BackingRow(
                    r.get("email", String::class.java)!!,
                    r.get("password_hash", String::class.java),
                    r.get("display_name", String::class.java)!!,
                    r.get("enabled", java.lang.Boolean::class.java)!!.booleanValue()
                )
            }.one()
            .awaitSingle()

    private suspend fun rolesOf(id: UUID): List<String> =
        db
            .sql(
                "SELECT r.name FROM ${schemas.metadata}.user_roles ur JOIN ${schemas.metadata}.roles r ON r.id = ur.role_id " +
                    "WHERE ur.user_id = :id ORDER BY r.name"
            ).bind("id", id)
            .map { r, _ -> r.get("name", String::class.java)!! }
            .all()
            .asFlow()
            .toList()

    // admin entries of the account, oldest first: operation, source, no user behind them
    private suspend fun entries(
        organizationId: UUID,
        id: UUID
    ): List<String> =
        db
            .sql(
                """
                SELECT operation, source, user_id IS NULL AS platform FROM ${schemas.metadata}.audit_log
                WHERE organization_id = :org AND object_name = 'admin:service-account' AND record_id = :id
                ORDER BY occurred_at
                """.trimIndent()
            ).bind("org", organizationId)
            .bind("id", id)
            .map { r, _ -> "${r.get("operation", String::class.java)} ${r.get("source", String::class.java)} platform=${r.get("platform")}" }
            .all()
            .asFlow()
            .toList()

    private suspend fun countAccountEntries(organizationId: UUID): Long =
        db
            .sql("SELECT count(*) AS n FROM ${schemas.metadata}.audit_log WHERE organization_id = :org AND object_name = 'admin:service-account'")
            .bind("org", organizationId)
            .map { r, _ -> r.get("n", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    private suspend fun localAccount(
        organizationId: UUID,
        name: String
    ): UUID {
        val id = UUID.randomUUID()
        db
            .sql(
                "INSERT INTO ${schemas.metadata}.users (id, organization_id, email, password_hash, display_name, enabled) " +
                    "VALUES (:id, :org, :email, 'x', :name, false)"
            ).bind("id", id)
            .bind("org", organizationId)
            .bind("email", ServiceAccountSecrets.backingEmail(id))
            .bind("name", name)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        db
            .sql("INSERT INTO ${schemas.metadata}.service_accounts (id, organization_id, name, secret_hash) VALUES (:id, :org, :name, 'hash')")
            .bind("id", id)
            .bind("org", organizationId)
            .bind("name", name)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return id
    }

    private fun found(resolution: RealmAccountResolution): RealmAccountResolution.Found {
        assertThat(resolution).isInstanceOf(RealmAccountResolution.Found::class.java)
        return resolution as RealmAccountResolution.Found
    }

    @Test
    fun `the first token of a configured client makes its row, backing user and roles`(): Unit =
        runBlocking {
            val org = organization("CARGA_DATOS", "OTRO")

            val account = found(carga.resolve(org, clientId)).account

            val rows = accountRows(org)
            assertThat(rows).hasSize(1)
            val row = rows.single()
            assertThat(row.id).isEqualTo(account.id)
            assertThat(row.name).isEqualTo(clientId)
            assertThat(row.externalClientId).isEqualTo(clientId)
            assertThat(row.secretHash).isNull()
            assertThat(row.enabled).isTrue()
            val user = backing(account.id)
            assertThat(user.email).isEqualTo(ServiceAccountSecrets.backingEmail(account.id))
            assertThat(user.passwordHash).isNull()
            assertThat(user.displayName).isEqualTo(clientId)
            assertThat(user.enabled).isFalse()
            assertThat(rolesOf(account.id)).containsExactly("CARGA_DATOS")
            assertThat(account.organizationId).isEqualTo(org)
            assertThat(account.name).isEqualTo(clientId)
            assertThat(account.email).isEqualTo(user.email)
            assertThat(account.roles).containsExactly("CARGA_DATOS")
            // one platform entry, labelled with the client
            assertThat(entries(org, account.id)).containsExactly("CREATE realm:$clientId platform=true")
        }

    @Test
    fun `two first tokens at once make one row`(): Unit =
        runBlocking {
            val org = organization("CARGA_DATOS")

            val results =
                coroutineScope {
                    (1..4).map { async(Dispatchers.IO) { carga.resolve(org, clientId) } }.awaitAll()
                }

            val ids = results.map { found(it).account.id }.toSet()
            assertThat(ids).hasSize(1)
            assertThat(accountRows(org)).hasSize(1)
            assertThat(entries(org, ids.single())).hasSize(1)
        }

    // keycloak gives a recreated client a new sub; the row is found by client id, never by sub
    @Test
    fun `a recreated client with the same id finds its row`(): Unit =
        runBlocking {
            val org = organization("CARGA_DATOS")

            val first = found(carga.resolve(org, clientId)).account
            val again = found(carga.resolve(org, clientId)).account

            assertThat(again).isEqualTo(first)
            assertThat(accountRows(org)).hasSize(1)
            // nothing changed, nothing written
            assertThat(entries(org, first.id)).hasSize(1)
        }

    @Test
    fun `a role the organization lacks writes nothing and says which`(): Unit =
        runBlocking {
            val org = organization("CARGA_DATOS")
            val wanting = accounts(RealmServiceAccount(clientId, listOf("CARGA_DATOS", "SRTM_LECTOR")))

            val resolution = wanting.resolve(org, clientId)

            assertThat(resolution).isEqualTo(RealmAccountResolution.Denied("role SRTM_LECTOR of client $clientId is not in this organization"))
            assertThat(accountRows(org)).isEmpty()
            assertThat(countAccountEntries(org)).isZero()
        }

    @Test
    fun `roles follow the configuration`(): Unit =
        runBlocking {
            val org = organization("CARGA_DATOS", "SRTM_LECTOR", "OTRO")
            val id = found(carga.resolve(org, clientId)).account.id

            val changed = accounts(RealmServiceAccount(clientId, listOf("srtm_lector", "OTRO")))
            val account = found(changed.resolve(org, clientId)).account

            assertThat(account.id).isEqualTo(id)
            assertThat(account.roles).containsExactly("OTRO", "SRTM_LECTOR")
            assertThat(rolesOf(id)).containsExactly("OTRO", "SRTM_LECTOR")
            assertThat(entries(org, id)).containsExactly("CREATE realm:$clientId platform=true", "UPDATE realm:$clientId platform=true")
            // the same set again: no write
            found(changed.resolve(org, clientId))
            assertThat(entries(org, id)).hasSize(2)
        }

    // names that a collation ignoring "_" orders against code points: the second resolve after the cache is no write
    @Test
    fun `roles that sort differently by collation are compared as a set`(): Unit =
        runBlocking {
            val org = organization("SRTM_LECTOR", "SRTMX")
            val both = accounts(RealmServiceAccount(clientId, listOf("SRTMX", "SRTM_LECTOR")))
            val id = found(both.resolve(org, clientId)).account.id

            val again = found(both.resolve(org, clientId)).account

            assertThat(again.id).isEqualTo(id)
            assertThat(again.roles).containsExactly("SRTMX", "SRTM_LECTOR")
            assertThat(entries(org, id)).containsExactly("CREATE realm:$clientId platform=true")
            assertThat(rolesOf(id)).containsExactlyInAnyOrder("SRTM_LECTOR", "SRTMX")
        }

    @Test
    fun `a local account with the client's name is not adopted`(): Unit =
        runBlocking {
            val org = organization("CARGA_DATOS")
            val local = localAccount(org, clientId)

            val resolution = carga.resolve(org, clientId)

            assertThat(resolution).isEqualTo(RealmAccountResolution.Denied("a local service account of this organization is already named $clientId"))
            assertThat(accountRows(org).map { it.id }).containsExactly(local)
            assertThat(rolesOf(local)).isEmpty()
            assertThat(countAccountEntries(org)).isZero()
        }

    @Test
    fun `a disabled row is denied`(): Unit =
        runBlocking {
            val org = organization("CARGA_DATOS")
            val id = found(carga.resolve(org, clientId)).account.id
            db
                .sql("UPDATE ${schemas.metadata}.service_accounts SET enabled = false WHERE id = :id AND organization_id = :org")
                .bind("id", id)
                .bind("org", org)
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()

            assertThat(carga.resolve(org, clientId)).isEqualTo(RealmAccountResolution.Denied("service account $clientId is disabled in this app"))
        }

    @Test
    fun `the same client in another organization is another row`(): Unit =
        runBlocking {
            val perene = organization("CARGA_DATOS")
            val satipo = organization("CARGA_DATOS")

            val inPerene = found(carga.resolve(perene, clientId)).account
            val inSatipo = found(carga.resolve(satipo, clientId)).account

            assertThat(inSatipo.id).isNotEqualTo(inPerene.id)
            assertThat(inSatipo.organizationId).isEqualTo(satipo)
            assertThat(accountRows(perene).map { it.id }).containsExactly(inPerene.id)
            assertThat(accountRows(satipo).map { it.id }).containsExactly(inSatipo.id)
        }

    @Test
    fun `a client the app does not list is denied without a look at the database`(): Unit =
        runBlocking {
            val org = organization("CARGA_DATOS")

            assertThat(
                carga.resolve(org, "rentas-perene")
            ).isEqualTo(RealmAccountResolution.Denied("client rentas-perene is not a service account of this app"))
            assertThat(accountRows(org)).isEmpty()
        }

    // a local account made at the same instant, after the check: the unique name says no, a denial and never a 500
    @Test
    fun `a local account made at the same instant is a denial, not an error`(): Unit =
        runBlocking {
            val org = organization("CARGA_DATOS")
            val operator = TransactionalOperator.create(transactionManager)

            val resolution =
                coroutineScope {
                    val pending = async(Dispatchers.IO) { carga.resolve(org, clientId) }
                    // uncommitted: the resolve does not see it, its insert waits on it and then fails on the name
                    operator.executeAndAwait {
                        localAccount(org, clientId)
                        delay(1500)
                    }
                    pending.await()
                }

            assertThat(resolution).isEqualTo(RealmAccountResolution.Denied("a local service account of this organization is already named $clientId"))
            assertThat(accountRows(org).map { it.externalClientId }).containsExactly(null)
        }

    // a role deleted after the check and before the write is not reported as held
    @Test
    fun `the answer carries the roles read after the write`(): Unit =
        runBlocking {
            val org = organization("CARGA_DATOS", "OTRO")
            val both = accounts(RealmServiceAccount(clientId, listOf("CARGA_DATOS", "OTRO")))
            val operator = TransactionalOperator.create(transactionManager)

            val resolution =
                coroutineScope {
                    val pending =
                        async(Dispatchers.IO) {
                            // the role check passes, then the lock holds it back
                            delay(300)
                            both.resolve(org, clientId)
                        }
                    operator.executeAndAwait {
                        lock.withXactLock("wasichai:realm-service-account:$org:$clientId") {
                            delay(1500)
                            db
                                .sql("DELETE FROM ${schemas.metadata}.roles WHERE organization_id = :org AND name = 'OTRO'")
                                .bind("org", org)
                                .fetch()
                                .rowsUpdated()
                                .awaitSingle()
                        }
                    }
                    pending.await()
                }

            val account = found(resolution).account
            assertThat(account.roles).containsExactly("CARGA_DATOS")
            assertThat(rolesOf(account.id)).containsExactly("CARGA_DATOS")
        }
}
