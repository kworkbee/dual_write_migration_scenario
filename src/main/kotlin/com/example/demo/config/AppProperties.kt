package com.example.demo.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.datasource")
data class MultiDataSourceProperties(
    val oldPrimary: DataSourceSettings,
    val oldSecondary: DataSourceSettings,
    val new: DataSourceSettings
)

data class DataSourceSettings(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int,
    val minimumIdle: Int,
    val connectionTimeoutMs: Long
)

@ConfigurationProperties("app.migration")
data class MigrationProperties(
    val flagKey: String,
    val async: AsyncExecutorSettings,
    val flagd: FlagdSettings
)

data class AsyncExecutorSettings(
    val corePoolSize: Int,
    val maxPoolSize: Int,
    val queueCapacity: Int
)

data class FlagdSettings(
    val host: String,
    val port: Int
)
