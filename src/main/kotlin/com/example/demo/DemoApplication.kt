package com.example.demo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.core.Ordered
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.transaction.annotation.EnableTransactionManagement

@SpringBootApplication
@EnableAsync
@ConfigurationPropertiesScan
@EnableTransactionManagement(order = Ordered.HIGHEST_PRECEDENCE + 10)
class DemoApplication

fun main(args: Array<String>) {
	runApplication<DemoApplication>(*args)
}
