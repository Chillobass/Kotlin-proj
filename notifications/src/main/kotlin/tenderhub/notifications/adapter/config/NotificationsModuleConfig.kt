package tenderhub.notifications.adapter.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import tenderhub.bidding.api.BidderDirectory
import tenderhub.bidding.api.TenderResultsPublished
import tenderhub.common.TransactionRunner
import tenderhub.notifications.application.NotificationRepository
import tenderhub.notifications.application.NotificationService
import tenderhub.tenders.api.TenderApproved
import tenderhub.tenders.api.TenderCancelled
import tenderhub.tenders.api.TenderRejected
import tenderhub.tenders.api.TenderSubmitted
import tenderhub.users.api.UserDirectory
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class NotificationsModuleConfig {
    @Bean
    fun notificationService(
        notifications: NotificationRepository,
        users: UserDirectory,
        bidders: BidderDirectory,
        tx: TransactionRunner,
        clock: Clock,
    ) = NotificationService(notifications, users, bidders, tx, clock)
}

@Component
class NotificationEventListener(private val notificationService: NotificationService) {
    @EventListener
    fun on(event: TenderSubmitted) = notificationService.onTenderSubmitted(event)

    @EventListener
    fun on(event: TenderApproved) = notificationService.onTenderApproved(event)

    @EventListener
    fun on(event: TenderRejected) = notificationService.onTenderRejected(event)

    @EventListener
    fun on(event: TenderCancelled) = notificationService.onTenderCancelled(event)

    @EventListener
    fun on(event: TenderResultsPublished) = notificationService.onResultsPublished(event)
}
