package com.example.demo.config

import dev.openfeature.contrib.providers.flagd.FlagdOptions
import dev.openfeature.contrib.providers.flagd.FlagdProvider
import dev.openfeature.sdk.OpenFeatureAPI
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenFeatureConfig {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun openFeatureApi(migrationProperties: MigrationProperties): OpenFeatureAPI {
        val api = OpenFeatureAPI.getInstance()
        val options = FlagdOptions.builder()
            .host(migrationProperties.flagd.host)
            .port(migrationProperties.flagd.port)
            .deadline(5000)
            .streamDeadlineMs(5000)
            .build()
        api.setProvider(FlagdProvider(options))
        logger.info(
            "Configured flagd provider for {}:{} with startup fallback to default variants",
            migrationProperties.flagd.host,
            migrationProperties.flagd.port
        )
        return api
    }
}
