package tenderhub.bidding.adapter.config

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tenderhub.bidding.application.BidQueries
import tenderhub.bidding.application.BidRepository
import tenderhub.bidding.application.BidService
import tenderhub.bidding.application.ResultsService
import tenderhub.bidding.application.TenderClosingService
import tenderhub.common.EventPublisher
import tenderhub.common.TransactionRunner
import tenderhub.tenders.api.TenderCatalog
import tenderhub.users.api.UserBlocked
import tenderhub.users.api.UserDirectory
import java.time.Clock

@ConfigurationProperties("tenderhub.closing")
data class ClosingProperties(
    val enabled: Boolean = true,
    val pollIntervalMs: Long = 10_000,
    val batchSize: Int = 100,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClosingProperties::class)
class BiddingModuleConfig {
    @Bean
    fun bidQueries(bids: BidRepository, tx: TransactionRunner) = BidQueries(bids, tx)

    @Bean
    fun bidService(bids: BidRepository, tenders: TenderCatalog, tx: TransactionRunner, clock: Clock) =
        BidService(bids, tenders, tx, clock)

    @Bean
    fun resultsService(bids: BidRepository, tenders: TenderCatalog, users: UserDirectory, tx: TransactionRunner) =
        ResultsService(bids, tenders, users, tx)

    @Bean
    fun tenderClosingService(
        bids: BidRepository,
        tenders: TenderCatalog,
        tx: TransactionRunner,
        events: EventPublisher,
        clock: Clock,
        properties: ClosingProperties,
    ) = TenderClosingService(bids, tenders, tx, events, clock, properties.batchSize)
}

@Component
@ConditionalOnProperty(prefix = "tenderhub.closing", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class TenderClosingScheduler(private val closingService: TenderClosingService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${tenderhub.closing.poll-interval-ms:10000}",
        initialDelayString = "\${tenderhub.closing.poll-interval-ms:10000}",
    )
    fun run() {
        try {
            val closed = closingService.closeExpired()
            if (closed > 0) log.info("Closing job: {} tender(s) closed", closed)
        } catch (e: Exception) {
            log.error("Closing job failed", e)
        }
    }
}

@Component
class BiddingEventListener(private val bidService: BidService) {
    @EventListener
    fun onUserBlocked(event: UserBlocked) {
        bidService.withdrawAllOfBlockedParticipant(event.userId)
    }
}
