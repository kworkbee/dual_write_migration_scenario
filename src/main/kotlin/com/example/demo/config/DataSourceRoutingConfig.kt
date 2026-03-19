package com.example.demo.config

import com.example.demo.domain.Board
import com.example.demo.domain.Member
import com.example.demo.domain.Post
import com.example.demo.domain.PostComment
import com.example.demo.service.MigrationRouteContext
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Executor
import javax.sql.DataSource

enum class RoutingDataSourceKey {
    OLD_PRIMARY,
    OLD_SECONDARY,
    NEW
}

class OldClusterRoutingDataSource : AbstractRoutingDataSource() {
    override fun determineCurrentLookupKey(): Any =
        if (MigrationRouteContext.currentTarget() == RoutingDataSourceKey.NEW) {
            RoutingDataSourceKey.NEW
        } else if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            RoutingDataSourceKey.OLD_SECONDARY
        } else {
            RoutingDataSourceKey.OLD_PRIMARY
        }
}

@Configuration
@EnableJpaRepositories(
    basePackages = ["com.example.demo.repository"],
    entityManagerFactoryRef = "routingEntityManagerFactory",
    transactionManagerRef = "routingTransactionManager"
)
class DataSourceRoutingConfig {

    @Bean
    fun hikariDataSourceFactory(): (String, DataSourceSettings, MeterRegistry) -> HikariDataSource =
        { poolName, settings, registry ->
            val config = HikariConfig().apply {
                this.poolName = poolName
                jdbcUrl = settings.jdbcUrl
                username = settings.username
                password = settings.password
                maximumPoolSize = settings.maximumPoolSize
                minimumIdle = settings.minimumIdle
                connectionTimeout = settings.connectionTimeoutMs
                isAutoCommit = false
                initializationFailTimeout = -1
                metricRegistry = registry
            }
            HikariDataSource(config)
        }

    @Bean("oldPrimaryDataSource")
    fun oldPrimaryDataSource(
        properties: MultiDataSourceProperties,
        meterRegistry: MeterRegistry,
        hikariDataSourceFactory: (String, DataSourceSettings, MeterRegistry) -> HikariDataSource
    ): DataSource = hikariDataSourceFactory("old-primary", properties.oldPrimary, meterRegistry)

    @Bean("oldSecondaryDataSource")
    fun oldSecondaryDataSource(
        properties: MultiDataSourceProperties,
        meterRegistry: MeterRegistry,
        hikariDataSourceFactory: (String, DataSourceSettings, MeterRegistry) -> HikariDataSource
    ): DataSource = hikariDataSourceFactory("old-secondary", properties.oldSecondary, meterRegistry)

    @Bean("newDataSource")
    fun newDataSource(
        properties: MultiDataSourceProperties,
        meterRegistry: MeterRegistry,
        hikariDataSourceFactory: (String, DataSourceSettings, MeterRegistry) -> HikariDataSource
    ): DataSource = hikariDataSourceFactory("new-db", properties.new, meterRegistry)

    @Primary
    @Bean("routingDataSource")
    fun routingDataSource(
        @Qualifier("oldPrimaryDataSource") oldPrimaryDataSource: DataSource,
        @Qualifier("oldSecondaryDataSource") oldSecondaryDataSource: DataSource,
        @Qualifier("newDataSource") newDataSource: DataSource
    ): DataSource =
        OldClusterRoutingDataSource().apply {
            setTargetDataSources(
                mapOf(
                    RoutingDataSourceKey.OLD_PRIMARY to oldPrimaryDataSource,
                    RoutingDataSourceKey.OLD_SECONDARY to oldSecondaryDataSource,
                    RoutingDataSourceKey.NEW to newDataSource
                )
            )
            setDefaultTargetDataSource(oldPrimaryDataSource)
            afterPropertiesSet()
        }

    @Primary
    @Bean("routingEntityManagerFactory")
    fun routingEntityManagerFactory(
        @Qualifier("routingDataSource") routingDataSource: DataSource
    ): LocalContainerEntityManagerFactoryBean = entityManagerFactory("routing-unit", routingDataSource, "update")

    @Bean("secondaryEntityManagerFactory")
    fun secondaryEntityManagerFactory(
        @Qualifier("oldSecondaryDataSource") dataSource: DataSource
    ): LocalContainerEntityManagerFactoryBean = entityManagerFactory("secondary-unit", dataSource, "update")

    @Bean("newEntityManagerFactory")
    fun newEntityManagerFactory(
        @Qualifier("newDataSource") dataSource: DataSource
    ): LocalContainerEntityManagerFactoryBean = entityManagerFactory("new-unit", dataSource, "update")

    @Primary
    @Bean("routingTransactionManager")
    fun routingTransactionManager(
        @Qualifier("routingEntityManagerFactory") entityManagerFactory: EntityManagerFactory
    ): PlatformTransactionManager = JpaTransactionManager(entityManagerFactory)

    @Bean("secondaryTransactionManager")
    fun secondaryTransactionManager(
        @Qualifier("secondaryEntityManagerFactory") entityManagerFactory: EntityManagerFactory
    ): PlatformTransactionManager = JpaTransactionManager(entityManagerFactory)

    @Bean("newTransactionManager")
    fun newTransactionManager(
        @Qualifier("newEntityManagerFactory") entityManagerFactory: EntityManagerFactory
    ): PlatformTransactionManager = JpaTransactionManager(entityManagerFactory)

    @Bean("migrationTaskExecutor")
    fun migrationTaskExecutor(
        migrationProperties: MigrationProperties,
        meterRegistry: MeterRegistry
    ): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.setThreadNamePrefix("migration-async-")
        executor.corePoolSize = migrationProperties.async.corePoolSize
        executor.maxPoolSize = migrationProperties.async.maxPoolSize
        executor.queueCapacity = migrationProperties.async.queueCapacity
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(10)
        executor.initialize()

        Gauge.builder("async.executor.pool.size") { executor.threadPoolExecutor.poolSize.toDouble() }
            .tag("executor", "migrationTaskExecutor")
            .register(meterRegistry)
        Gauge.builder("async.executor.active.count") { executor.activeCount.toDouble() }
            .tag("executor", "migrationTaskExecutor")
            .register(meterRegistry)
        Gauge.builder("async.executor.queue.size") { executor.threadPoolExecutor.queue.size.toDouble() }
            .tag("executor", "migrationTaskExecutor")
            .register(meterRegistry)
        Gauge.builder("async.executor.completed.tasks") { executor.threadPoolExecutor.completedTaskCount.toDouble() }
            .tag("executor", "migrationTaskExecutor")
            .register(meterRegistry)
        return executor
    }

    @Bean
    fun hikariMetricsBinder(
        meterRegistry: MeterRegistry,
        @Qualifier("oldPrimaryDataSource") oldPrimaryDataSource: DataSource,
        @Qualifier("oldSecondaryDataSource") oldSecondaryDataSource: DataSource,
        @Qualifier("newDataSource") newDataSource: DataSource
    ): HikariMetricsBinder {
        return HikariMetricsBinder(meterRegistry, listOf(
            "old-primary" to oldPrimaryDataSource,
            "old-secondary" to oldSecondaryDataSource,
            "new" to newDataSource
        ))
    }

    private fun entityManagerFactory(
        persistenceUnitName: String,
        dataSource: DataSource,
        ddlAuto: String
    ): LocalContainerEntityManagerFactoryBean =
        LocalContainerEntityManagerFactoryBean().apply {
            this.persistenceUnitName = persistenceUnitName
            this.dataSource = dataSource
            setPackagesToScan(Member::class.java.packageName)
            jpaVendorAdapter = HibernateJpaVendorAdapter()
            setJpaPropertyMap(
                mapOf(
                    "hibernate.hbm2ddl.auto" to ddlAuto,
                    "hibernate.show_sql" to false,
                    "hibernate.jdbc.time_zone" to "UTC"
                )
            )
        }
}

class HikariMetricsBinder(
    meterRegistry: MeterRegistry,
    dataSources: List<Pair<String, DataSource>>
) {
    init {
        dataSources.forEach { (name, dataSource) ->
            Gauge.builder("datasource.$name.connections.active") { unwrap(dataSource)?.hikariPoolMXBean?.activeConnections?.toDouble() ?: 0.0 }
                .register(meterRegistry)
            Gauge.builder("datasource.$name.connections.idle") { unwrap(dataSource)?.hikariPoolMXBean?.idleConnections?.toDouble() ?: 0.0 }
                .register(meterRegistry)
            Gauge.builder("datasource.$name.connections.pending") { unwrap(dataSource)?.hikariPoolMXBean?.threadsAwaitingConnection?.toDouble() ?: 0.0 }
                .register(meterRegistry)
            Gauge.builder("datasource.$name.connections.total") { unwrap(dataSource)?.hikariPoolMXBean?.totalConnections?.toDouble() ?: 0.0 }
                .register(meterRegistry)
        }
    }

    private fun unwrap(dataSource: DataSource): HikariDataSource? =
        runCatching { dataSource.unwrap(HikariDataSource::class.java) }.getOrNull()
}
