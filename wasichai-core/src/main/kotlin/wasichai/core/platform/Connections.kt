package wasichai.core.platform

import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Wrapped

object Connections {
    // the factory under the pool: a connection that holds session state (an advisory lock, a LISTEN)
    // must never go back to the pool. users: ClusterLock's lease, the notifications LISTEN connection.
    fun unpooled(factory: ConnectionFactory): ConnectionFactory {
        var current = factory
        while (current is Wrapped<*>) current = (current.unwrap() as? ConnectionFactory) ?: break
        return current
    }
}
