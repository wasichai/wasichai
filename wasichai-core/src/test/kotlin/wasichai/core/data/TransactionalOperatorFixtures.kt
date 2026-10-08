package wasichai.core.data

import org.mockito.Mockito.mock
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.reactive.TransactionCallback
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

object TransactionalOperatorFixtures {
    // runs the block as is: no database here, so nothing to begin, commit or roll back. [opened] counts the blocks
    class Inline : TransactionalOperator {
        var opened = 0
            private set

        override fun <T : Any> transactional(mono: Mono<T>): Mono<T> = mono

        override fun <T : Any> execute(action: TransactionCallback<T>): Flux<T> {
            opened++
            return Flux.from(action.doInTransaction(mock(ReactiveTransaction::class.java)))
        }
    }

    fun inline(): Inline = Inline()
}
