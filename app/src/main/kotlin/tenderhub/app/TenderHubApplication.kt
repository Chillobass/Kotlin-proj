package tenderhub.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["tenderhub"])
@EnableScheduling
class TenderHubApplication

fun main(args: Array<String>) {
    runApplication<TenderHubApplication>(*args)
}
