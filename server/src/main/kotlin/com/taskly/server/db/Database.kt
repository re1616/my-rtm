package com.taskly.server.db

import com.taskly.server.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource

object Database {
    private var dataSource: HikariDataSource? = null
    private var dslContext: DSLContext? = null

    fun init(config: DatabaseConfig) {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://${config.host}:${config.port}/${config.database}"
            username = config.user
            password = config.password
            maximumPoolSize = config.poolSize
            minimumIdle = 2
            idleTimeout = 30_000
            connectionTimeout = 10_000
            validationTimeout = 3_000
            maxLifetime = 600_000
            isAutoCommit = true
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }

        val ds = HikariDataSource(hikariConfig)
        dataSource = ds

        // Run Flyway migrations
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        dslContext = DSL.using(ds, SQLDialect.POSTGRES)
    }

    fun ds(): DataSource = dataSource ?: throw IllegalStateException("Database not initialized")
    fun ctx(): DSLContext = dslContext ?: throw IllegalStateException("Database not initialized")

    fun shutdown() {
        dataSource?.close()
    }
}