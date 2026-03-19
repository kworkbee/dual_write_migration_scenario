package com.example.demo.service

import com.example.demo.config.MigrationProperties
import dev.openfeature.sdk.Client
import dev.openfeature.sdk.OpenFeatureAPI
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

enum class MigrationMode {
    OLD,
    DUAL_WRITE,
    NEW
}

@Component
class MigrationModeResolver(
    openFeatureAPI: OpenFeatureAPI,
    private val migrationProperties: MigrationProperties,
    meterRegistry: MeterRegistry,
    private val migrationMetrics: MigrationMetrics
) {
    private val client: Client = openFeatureAPI.getClient("zero-downtime-simulator")
    private val evaluations: Map<MigrationMode, Counter> = MigrationMode.entries.associateWith { mode ->
        Counter.builder("migration.mode.evaluations")
            .tag("mode", mode.name)
            .register(meterRegistry)
    }
    @Volatile
    private var lastMode: MigrationMode? = null

    fun currentMode(): MigrationMode {
        val raw = client.getStringValue(migrationProperties.flagKey, MigrationMode.OLD.name)
        val mode = MigrationMode.entries.firstOrNull { it.name == raw } ?: MigrationMode.OLD
        evaluations.getValue(mode).increment()
        migrationMetrics.setCurrentMode(mode)
        val previous = lastMode
        if (previous != null && previous != mode) {
            migrationMetrics.incrementModeTransition(previous, mode)
        }
        lastMode = mode
        return mode
    }
}
