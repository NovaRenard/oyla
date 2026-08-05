package kz.oyla.server.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

object DatabaseFactory {
    fun initialize(): Database {
        val production = System.getenv("OYLA_ENV")?.equals("production", ignoreCase = true) == true
        fun databaseValue(name: String, developmentDefault: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: if (production) {
                throw IllegalStateException("$name must be set when OYLA_ENV=production")
            } else developmentDefault
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = databaseValue("DATABASE_URL", "jdbc:postgresql://localhost:5432/oyla")
            username = databaseValue("DATABASE_USER", "oyla")
            password = databaseValue("DATABASE_PASSWORD", "oyla")
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 8
            minimumIdle = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }
        val dataSource = HikariDataSource(hikariConfig)
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return Database.connect(dataSource)
    }
}
