package tenderhub.app.infrastructure

import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tenderhub.common.DomainEvent
import tenderhub.common.EventPublisher
import tenderhub.common.TransactionRunner
import java.time.Clock

class SpringTransactionRunner(transactionManager: PlatformTransactionManager) : TransactionRunner {
    private val readWrite = TransactionTemplate(transactionManager)
    private val readOnlyTemplate = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Suppress("UNCHECKED_CAST")
    override fun <T> inTransaction(block: () -> T): T = readWrite.execute { block() } as T

    @Suppress("UNCHECKED_CAST")
    override fun <T> readOnly(block: () -> T): T = readOnlyTemplate.execute { block() } as T
}

class SpringEventPublisher(private val publisher: ApplicationEventPublisher) : EventPublisher {
    override fun publish(event: DomainEvent) = publisher.publishEvent(event)
}

@Configuration(proxyBeanMethods = false)
class PlatformConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun transactionRunner(transactionManager: PlatformTransactionManager): TransactionRunner =
        SpringTransactionRunner(transactionManager)

    @Bean
    fun eventPublisher(publisher: ApplicationEventPublisher): EventPublisher = SpringEventPublisher(publisher)
}
