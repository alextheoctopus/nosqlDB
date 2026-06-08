import com.mongodb.kotlin.client.MongoClient
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.CqlSession
import java.net.InetSocketAddress
import com.datastax.oss.driver.api.core.session.SessionBuilder
import com.datastax.oss.driver.api.core.CqlSessionBuilder

import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase

fun createNeo4jDriver(config: AppConfig): Driver {
    return GraphDatabase.driver(
        config.neo4jUrl,
        AuthTokens.basic(config.neo4jUsername, config.neo4jPassword)
    )
}

fun createJedisPool(config: AppConfig): JedisPool {
    val poolConfig = JedisPoolConfig().apply {
        maxTotal = 8
        maxIdle = 8
        minIdle = 0
    }

    return if (!config.redisPassword.isNullOrBlank()) {
        JedisPool(poolConfig, config.redisHost, config.redisPort, 2_000, config.redisPassword, config.redisDb)
    } else {
        JedisPool(poolConfig, config.redisHost, config.redisPort, 2_000, null, config.redisDb)
    }
}


fun createMongoClient(config: AppConfig): MongoClient {
    val connectionString =
        if (!config.mongoUser.isNullOrBlank() && !config.mongoPassword.isNullOrBlank()) {
            "mongodb://${config.mongoUser}:${config.mongoPassword}@${config.mongoHost}:${config.mongoPort}/${config.mongoDatabase}?authSource=${config.mongoDatabase}"
        } else {
            "mongodb://${config.mongoHost}:${config.mongoPort}/${config.mongoDatabase}"
        }

    return MongoClient.create(connectionString)
}

fun createCassandraSession(config: AppConfig): CqlSession {
    val session = buildCassandraSession(config, keyspace = config.cassandraKeyspace)
    return session
}

private fun buildCassandraSession(config: AppConfig, keyspace: String?): CqlSession {
    val builder = CqlSession.builder()
        .withLocalDatacenter("dc1")
        .withConfigLoader(
            com.datastax.oss.driver.api.core.config.DriverConfigLoader.programmaticBuilder()
                .withDuration(
                    com.datastax.oss.driver.api.core.config.DefaultDriverOption.REQUEST_TIMEOUT,
                    java.time.Duration.ofSeconds(10)
                )
                .build()
        )

    config.cassandraHosts.forEach { host ->
        builder.addContactPoint(InetSocketAddress(host, config.cassandraPort))
    }

    if (!config.cassandraUsername.isNullOrBlank() && !config.cassandraPassword.isNullOrBlank()) {
        builder.withAuthCredentials(config.cassandraUsername, config.cassandraPassword)
    }

    if (!keyspace.isNullOrBlank()) {
        builder.withKeyspace(CqlIdentifier.fromCql(keyspace))
    }

    return buildWithRetry(builder)
}

private fun buildWithRetry(builder: CqlSessionBuilder): CqlSession {
    var lastError: Exception? = null

    repeat(30) { attempt ->
        try {
            return builder.build()
        } catch (e: Exception) {
            lastError = e
            println("Cassandra is not ready yet, retry ${attempt + 1}/30")
            Thread.sleep(2_000)
        }
    }

    throw lastError ?: error("Failed to connect to Cassandra")
}
