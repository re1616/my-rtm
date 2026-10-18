package com.taskly.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.password4j.Password
import com.taskly.server.AuthConfig
import com.taskly.server.db.Database
import com.taskly.server.db.RefreshTokensTable
import com.taskly.server.db.UsersTable
import com.taskly.server.dto.AuthResponse
import com.taskly.server.dto.LoginRequest
import com.taskly.server.dto.RegisterRequest
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.*

object AuthService {
    private lateinit var authConfig: AuthConfig
    private lateinit var algorithm: Algorithm
    private val secureRandom = SecureRandom()

    fun init(config: AuthConfig) {
        authConfig = config
        algorithm = Algorithm.HMAC256(config.jwtSecret)
    }

    fun register(request: RegisterRequest): AuthResponse {
        return Database.ctx().transactionResult { config ->
            val tx = DSL.using(config)
            val existing = tx.select(DSL.count()).from(UsersTable.TABLE)
                .where(DSL.lower(UsersTable.EMAIL).eq(request.email.lowercase()))
                .fetchOneInto(Long::class.java) ?: 0L
            if (existing > 0) throw IllegalArgumentException("Email already registered")

            val passwordHash = Password.hash(request.password).addRandomSalt().withArgon2().result.toString()
            val userId = tx.insertInto(UsersTable.TABLE)
                .set(UsersTable.EMAIL, request.email.lowercase().trim())
                .set(UsersTable.PASSWORD_HASH, passwordHash)
                .set(UsersTable.TZ, request.tz ?: "Europe/Moscow")
                .set(UsersTable.LOCALE, request.locale ?: "ru")
                .returning(UsersTable.ID)
                .fetchOne()
                ?.get(UsersTable.ID)
                ?: throw IllegalStateException("Failed to create user")

            val accessToken = JWT.create()
                .withSubject("$userId")
                .withIssuedAt(Date.from(Instant.now()))
                .withExpiresAt(Date.from(Instant.now().plus(Duration.ofMinutes(authConfig.accessTokenExpiryMinutes))))
                .withIssuer("taskly")
                .sign(algorithm)

            val refreshToken = generateRefreshTokenInTx(tx, userId)

            AuthResponse(accessToken, refreshToken, "$userId")
        }
    }

    fun login(request: LoginRequest): AuthResponse {
        val ctx = Database.ctx()
        val user = ctx.selectFrom(UsersTable.TABLE)
            .where(DSL.lower(UsersTable.EMAIL).eq(request.email.lowercase().trim()))
            .fetchOne() ?: throw IllegalArgumentException("Invalid email or password")

        val isMatch = Password.check(request.password, user.get(UsersTable.PASSWORD_HASH)).withArgon2()
        if (!isMatch) throw IllegalArgumentException("Invalid email or password")

        val userId = user.get(UsersTable.ID)
        val accessToken = JWT.create()
            .withSubject("$userId")
            .withIssuedAt(Date.from(Instant.now()))
            .withExpiresAt(Date.from(Instant.now().plus(Duration.ofMinutes(authConfig.accessTokenExpiryMinutes))))
            .withIssuer("taskly")
            .sign(algorithm)

        val refreshToken = generateRefreshTokenInTx(ctx, userId)
        return AuthResponse(accessToken, refreshToken, "$userId")
    }

    fun refreshToken(refreshToken: String): AuthResponse {
        val ctx = Database.ctx()
        val tokenHash = sha256(refreshToken)
        val record = ctx.selectFrom(RefreshTokensTable.TABLE)
            .where(RefreshTokensTable.TOKEN_HASH.eq(tokenHash))
            .fetchOne() ?: throw IllegalArgumentException("Invalid or expired refresh token")

        // SEC-5: replay detection — reused token that was already revoked means theft
        if (record.get(RefreshTokensTable.REVOKED)) {
            // Replay: steal scenario — revoke all tokens for this user
            val userId = record.get(RefreshTokensTable.USER_ID)
            ctx.update(RefreshTokensTable.TABLE)
                .set(RefreshTokensTable.REVOKED, true)
                .where(RefreshTokensTable.USER_ID.eq(userId))
                .execute()
            throw IllegalArgumentException("Refresh token replay detected: all tokens revoked for security")
        }

        if (record.get(RefreshTokensTable.EXPIRES_AT).isBefore(OffsetDateTime.now())) {
            throw IllegalArgumentException("Invalid or expired refresh token")
        }

        val userId = record.get(RefreshTokensTable.USER_ID)

        // Revoke the used token before issuing a new one
        ctx.update(RefreshTokensTable.TABLE)
            .set(RefreshTokensTable.REVOKED, true)
            .where(RefreshTokensTable.ID.eq(record.get(RefreshTokensTable.ID)))
            .execute()

        val accessToken = JWT.create()
            .withSubject("$userId")
            .withIssuedAt(Date.from(Instant.now()))
            .withExpiresAt(Date.from(Instant.now().plus(Duration.ofMinutes(authConfig.accessTokenExpiryMinutes))))
            .withIssuer("taskly")
            .sign(algorithm)

        val newRefreshToken = generateRefreshTokenInTx(ctx, userId)
        return AuthResponse(accessToken, newRefreshToken, "$userId")
    }

    fun logout(userId: String) {
        Database.ctx().update(RefreshTokensTable.TABLE)
            .set(RefreshTokensTable.REVOKED, true)
            .where(RefreshTokensTable.USER_ID.eq(UUID.fromString(userId)))
            .execute()
    }

    fun validateAccessToken(token: String): String {
        return try {
            val decoded = JWT.require(algorithm).withIssuer("taskly").build().verify(token)
            decoded.subject ?: throw IllegalArgumentException("Invalid token subject")
        } catch (e: JWTVerificationException) {
            throw IllegalArgumentException("Invalid or expired access token")
        }
    }

    private fun generateRefreshTokenInTx(ctx: DSLContext, userId: UUID): String {
        val tokenBytes = ByteArray(64).also { secureRandom.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val tokenHash = sha256(token)
        ctx.insertInto(RefreshTokensTable.TABLE)
            .set(RefreshTokensTable.USER_ID, userId)
            .set(RefreshTokensTable.TOKEN_HASH, tokenHash)
            .set(RefreshTokensTable.EXPIRES_AT, OffsetDateTime.now().plusDays(authConfig.refreshTokenExpiryDays.toLong()))
            .execute()
        return token
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}