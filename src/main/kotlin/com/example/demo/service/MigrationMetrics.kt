package com.example.demo.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Component
class MigrationMetrics(
    private val meterRegistry: MeterRegistry
) {
    private val currentModeOrdinal = AtomicInteger(MigrationMode.OLD.ordinal)
    private val counters = ConcurrentHashMap<String, Counter>()

    init {
        Gauge.builder("migration.mode.current.ordinal") { currentModeOrdinal.get().toDouble() }
            .description("Last evaluated migration mode ordinal: OLD=0, DUAL_WRITE=1, NEW=2")
            .register(meterRegistry)
    }

    fun setCurrentMode(mode: MigrationMode) {
        currentModeOrdinal.set(mode.ordinal)
    }

    fun incrementModeTransition(from: MigrationMode, to: MigrationMode) {
        counter(
            "migration.mode.transitions",
            "from", from.name,
            "to", to.name
        ).increment()
    }

    fun incrementServiceWrite(
        mode: MigrationMode,
        entity: String,
        operation: String,
        target: String,
        result: String
    ) {
        counter(
            "migration.service.write.operations",
            "mode", mode.name,
            "entity", entity,
            "operation", operation,
            "target", target,
            "result", result
        ).increment()
    }

    fun incrementReplicationPublished(entity: String, action: String) {
        counter(
            "migration.replication.published",
            "entity", entity,
            "action", action,
            "target", "new-db"
        ).increment()
    }

    fun incrementReplicationResult(entity: String, action: String, result: String) {
        counter(
            "migration.replication.events",
            "entity", entity,
            "action", action,
            "target", "new-db",
            "result", result
        ).increment()
    }

    private fun counter(name: String, vararg tags: String): Counter {
        val key = buildString {
            append(name)
            tags.forEach { append('|').append(it) }
        }
        return counters.computeIfAbsent(key) {
            val builder = Counter.builder(name)
            var index = 0
            while (index < tags.size) {
                builder.tag(tags[index], tags[index + 1])
                index += 2
            }
            builder.register(meterRegistry)
        }
    }
}
