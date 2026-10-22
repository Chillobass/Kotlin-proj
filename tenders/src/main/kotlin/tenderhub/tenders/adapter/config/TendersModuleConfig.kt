package tenderhub.tenders.adapter.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import tenderhub.common.EventPublisher
import tenderhub.common.TransactionRunner
import tenderhub.tenders.api.BidStatistics
import tenderhub.tenders.application.ModerationService
import tenderhub.tenders.application.TenderCatalogService
import tenderhub.tenders.application.TenderRepository
import tenderhub.tenders.application.TenderService
import tenderhub.tenders.domain.TenderPolicy
import tenderhub.users.api.UserBlocked
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("tenderhub.tender")
data class TenderProperties(
    val minDeadlineOffset: Duration = Duration.ofHours(24),
    val maxDeadlineOffset: Duration = Duration.ofDays(90),
    val minOpenDuration: Duration = Duration.ofHours(24),
    val maxOnReviewPerOwner: Int = 5,
) {
    fun toPolicy() = TenderPolicy(minDeadlineOffset, maxDeadlineOffset, minOpenDuration, maxOnReviewPerOwner)
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenderProperties::class)
class TendersModuleConfig {
    @Bean
    fun tenderPolicy(properties: TenderProperties): TenderPolicy = properties.toPolicy()

    @Bean
    fun tenderService(
        tenders: TenderRepository,
        bidStatistics: BidStatistics,
        policy: TenderPolicy,
        tx: TransactionRunner,
        events: EventPublisher,
        clock: Clock,
    ) = TenderService(tenders, bidStatistics, policy, tx, events, clock)

    @Bean
    fun moderationService(
        tenders: TenderRepository,
        bidStatistics: BidStatistics,
        policy: TenderPolicy,
        tx: TransactionRunner,
        events: EventPublisher,
        clock: Clock,
    ) = ModerationService(tenders, bidStatistics, policy, tx, events, clock)

    @Bean
    fun tenderCatalog(tenders: TenderRepository, tx: TransactionRunner) = TenderCatalogService(tenders, tx)
}

@Component
class TendersEventListener(private val tenderService: TenderService) {
    @EventListener
    fun onUserBlocked(event: UserBlocked) {
        tenderService.cancelAllOfBlockedOwner(event.userId, event.reason)
    }
}
