package tenderhub.app.web

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tenderhub.common.Actor

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {
    init {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Actor::class.java)
    }

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("TenderHub API")
                .version("v1")
                .description("Площадка для проведения закрытых тендеров: модерация, заявки, автоматический рейтинг"),
        )
        .components(
            Components().addSecuritySchemes(
                BASIC_AUTH,
                SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic"),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList(BASIC_AUTH))

    companion object {
        private const val BASIC_AUTH = "basicAuth"
    }
}
