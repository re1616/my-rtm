package com.taskly.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.password4j.Password
import com.taskly.server.AuthConfig
import com.taskly.server.db.Database
import com.taskly.server.db.PasswordResetTokensTable
import com.taskly.server.db.RefreshTokensTable
import com.taskly.server.db.UsersTable
import com.taskly.server.dto.AccountResponse
import com.taskly.server.dto.AuthResponse
import com.taskly.server.dto.TotpSetupResponse
import com.taskly.server.email.Email
import com.taskly.server.email.Mailer
import com.taskly.server.errors.ConflictException
import com.taskly.server.errors.NotFoundException
import com.taskly.server.errors.UnauthorizedException
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

        // Google-only accounts have no password until they set one via password reset
        val hash = user.get(UsersTable.PASSWORD_HASH) ?: throw IllegalArgumentException("Invalid email or password")
        val isMatch = Password.check(request.password, hash).withArgon2()
        if (!isMatch) throw IllegalArgumentException("Invalid email or password")

        requireSecondFactor(user, request.totpCode)
        return issueTokens(ctx, user.get(UsersTable.ID))
    }

    /** Throws 401 with code `totp_required` / `totp_invalid` when two-factor auth is on. */
    private fun requireSecondFactor(user: org.jooq.Record, code: String?) {
        if (!user.get(UsersTable.TOTP_ENABLED)) return
        if (code.isNullOrBlank()) throw UnauthorizedException("Two-factor authentication code required", "totp_required")
        val secret = user.get(UsersTable.TOTP_SECRET) ?: throw UnauthorizedException("Two-factor authentication is misconfigured", "totp_invalid")
        if (!Totp.verify(secret, code)) throw UnauthorizedException("Invalid two-factor authentication code", "totp_invalid")
    }

    private fun issueTokens(ctx: DSLContext, userId: UUID): AuthResponse {
        val accessToken = JWT.create()
            .withSubject("$userId")
            .withIssuedAt(Date.from(Instant.now()))
            .withExpiresAt(Date.from(Instant.now().plus(Duration.ofMinutes(authConfig.accessTokenExpiryMinutes))))
            .withIssuer("taskly")
            .sign(algorithm)
        return AuthResponse(accessToken, generateRefreshTokenInTx(ctx, userId), "$userId")
    }

    // ── Password reset ──────────────────────────────────────────

    /**
     * Emails a single-use reset link valid for 1 hour. Always succeeds from the caller's point
     * of view, so the endpoint does not reveal which emails are registered.
     */
    fun forgotPassword(email: String, appUrl: String) {
        val ctx = Database.ctx()
        val user = ctx.selectFrom(UsersTable.TABLE)
            .where(DSL.lower(UsersTable.EMAIL).eq(email.lowercase().trim()))
            .fetchOne() ?: return
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(secureRandom::nextBytes))
        ctx.insertInto(PasswordResetTokensTable.TABLE)
            .set(PasswordResetTokensTable.USER_ID, user.get(UsersTable.ID))
            .set(PasswordResetTokensTable.TOKEN_HASH, sha256(token))
            .set(PasswordResetTokensTable.EXPIRES_AT, OffsetDateTime.now().plusHours(1))
            .execute()
        Mailer.send(
            Email(
                to = user.get(UsersTable.EMAIL),
                subject = "Taskly: сброс пароля",
                text = "Чтобы задать новый пароль, откройте ссылку (действует 1 час):\n" +
                    "$appUrl/reset-password?token=$token\n\n" +
                    "Если вы не запрашивали сброс, просто проигнорируйте это письмо.",
            )
        )
    }

    /** Sets a new password and signs the user out everywhere. The token works once. */
    fun resetPassword(token: String, newPassword: String) {
        require(newPassword.length >= MIN_PASSWORD) { "Password must be at least $MIN_PASSWORD characters" }
        Database.ctx().transaction { config ->
            val tx = DSL.using(config)
            // Claim the token atomically: a second use (or a race) finds no unused, unexpired row
            val userId = tx.update(PasswordResetTokensTable.TABLE)
                .set(PasswordResetTokensTable.USED_AT, OffsetDateTime.now())
                .where(PasswordResetTokensTable.TOKEN_HASH.eq(sha256(token)))
                .and(PasswordResetTokensTable.USED_AT.isNull)
                .and(PasswordResetTokensTable.EXPIRES_AT.gt(OffsetDateTime.now()))
                .returning(PasswordResetTokensTable.USER_ID)
                .fetchOne()
                ?.get(PasswordResetTokensTable.USER_ID)
                ?: throw IllegalArgumentException("Reset link is invalid or has expired")
            tx.update(UsersTable.TABLE)
                .set(UsersTable.PASSWORD_HASH, Password.hash(newPassword).addRandomSalt().withArgon2().result.toString())
                .set(UsersTable.UPDATED_AT, OffsetDateTime.now())
                .where(UsersTable.ID.eq(userId))
                .execute()
            revokeAll(tx, userId)
        }
    }

    // ── Two-factor authentication (TOTP) ────────────────────────

    /** Generates a new secret (not active until confirmed with [enableTotp]). */
    fun setupTotp(userId: String): TotpSetupResponse {
        val id = UUID.fromString(userId)
        val ctx = Database.ctx()
        val user = ctx.selectFrom(UsersTable.TABLE).where(UsersTable.ID.eq(id)).fetchOne()
            ?: throw NotFoundException("User not found")
        if (user.get(UsersTable.TOTP_ENABLED)) throw ConflictException("Two-factor authentication is already enabled")
        val secret = Totp.newSecret()
        ctx.update(UsersTable.TABLE).set(UsersTable.TOTP_SECRET, secret).where(UsersTable.ID.eq(id)).execute()
        return TotpSetupResponse(secret, Totp.uri(secret, user.get(UsersTable.EMAIL)))
    }

    /** Turns two-factor auth on once the user proves the authenticator works. */
    fun enableTotp(userId: String, code: String) {
        val id = UUID.fromString(userId)
        val ctx = Database.ctx()
        val secret = ctx.select(UsersTable.TOTP_SECRET).from(UsersTable.TABLE).where(UsersTable.ID.eq(id))
            .fetchOne()?.value1() ?: throw IllegalArgumentException("Call /v1/auth/2fa/setup first")
        if (!Totp.verify(secret, code)) throw IllegalArgumentException("Invalid two-factor authentication code")
        ctx.update(UsersTable.TABLE).set(UsersTable.TOTP_ENABLED, true).where(UsersTable.ID.eq(id)).execute()
    }

    /** Turning two-factor auth off requires a valid current code. */
    fun disableTotp(userId: String, code: String) {
        val id = UUID.fromString(userId)
        val ctx = Database.ctx()
        val user = ctx.selectFrom(UsersTable.TABLE).where(UsersTable.ID.eq(id)).fetchOne()
            ?: throw NotFoundException("User not found")
        if (!user.get(UsersTable.TOTP_ENABLED)) return
        if (!Totp.verify(user.get(UsersTable.TOTP_SECRET) ?: "", code)) {
            throw IllegalArgumentException("Invalid two-factor authentication code")
        }
        ctx.update(UsersTable.TABLE)
            .set(UsersTable.TOTP_ENABLED, false)
            .setNull(UsersTable.TOTP_SECRET)
            .where(UsersTable.ID.eq(id))
            .execute()
    }

    fun account(userId: String): AccountResponse {
        val user = Database.ctx().selectFrom(UsersTable.TABLE).where(UsersTable.ID.eq(UUID.fromString(userId))).fetchOne()
            ?: throw NotFoundException("User not found")
        return AccountResponse(
            userId = userId,
            email = user.get(UsersTable.EMAIL),
            totpEnabled = user.get(UsersTable.TOTP_ENABLED),
            hasPassword = user.get(UsersTable.PASSWORD_HASH) != null,
            googleLinked = user.get(UsersTable.GOOGLE_SUB) != null,
        )
    }

    // ── Google sign-in ──────────────────────────────────────────

    /**
     * Signs in with a verified Google identity: an account already linked to this Google user,
     * else the account with the same (Google-verified) email gets linked, else a new account.
     */
    fun googleLogin(identity: GoogleIdentity, totpCode: String?): AuthResponse {
        if (!identity.emailVerified) throw IllegalArgumentException("Google account email is not verified")
        return Database.ctx().transactionResult { config ->
            val tx = DSL.using(config)
            val linked = tx.selectFrom(UsersTable.TABLE).where(UsersTable.GOOGLE_SUB.eq(identity.subject)).forUpdate().fetchOne()
            val user = linked
                ?: tx.selectFrom(UsersTable.TABLE).where(DSL.lower(UsersTable.EMAIL).eq(identity.email)).forUpdate().fetchOne()
                    ?.also { tx.update(UsersTable.TABLE).set(UsersTable.GOOGLE_SUB, identity.subject).where(UsersTable.ID.eq(it.get(UsersTable.ID))).execute() }
                ?: tx.insertInto(UsersTable.TABLE)
                    .set(UsersTable.EMAIL, identity.email)
                    .setNull(UsersTable.PASSWORD_HASH)
                    .set(UsersTable.GOOGLE_SUB, identity.subject)
                    .returning(UsersTable.TABLE.asterisk())
                    .fetchOne()!!
            requireSecondFactor(user, totpCode)
            issueTokens(tx, user.get(UsersTable.ID))
        }
    }

    fun refreshToken(refreshToken: String): AuthResponse {
        val ctx = Database.ctx()
        val tokenHash = sha256(refreshToken)
        val record = ctx.selectFrom(RefreshTokensTable.TABLE)
            .where(RefreshTokensTable.TOKEN_HASH.eq(tokenHash))
            .fetchOne() ?: throw IllegalArgumentException("Invalid or expired refresh token")

        val userId = record.get(RefreshTokensTable.USER_ID)

        // SEC-5: a revoked token presented again means it was stolen (or replayed) —
        // revoke the user's whole chain. Done outside a transaction so the revocation sticks.
        if (record.get(RefreshTokensTable.REVOKED)) {
            revokeAll(ctx, userId)
            throw IllegalArgumentException("Refresh token replay detected: all tokens revoked for security")
        }

        if (record.get(RefreshTokensTable.EXPIRES_AT).isBefore(OffsetDateTime.now())) {
            throw IllegalArgumentException("Invalid or expired refresh token")
        }

        // Claim the token atomically: only one of two concurrent requests with the same token
        // can flip revoked=false→true. The loser is a replay, not a second valid rotation.
        val claimed = ctx.update(RefreshTokensTable.TABLE)
            .set(RefreshTokensTable.REVOKED, true)
            .where(RefreshTokensTable.ID.eq(record.get(RefreshTokensTable.ID)))
            .and(RefreshTokensTable.REVOKED.isFalse)
            .execute()
        if (claimed == 0) {
            revokeAll(ctx, userId)
            throw IllegalArgumentException("Refresh token replay detected: all tokens revoked for security")
        }

        val accessToken = JWT.create()
            .withSubject("$userId")
            .withIssuedAt(Date.from(Instant.now()))
            .withExpiresAt(Date.from(Instant.now().plus(Duration.ofMinutes(authConfig.accessTokenExpiryMinutes))))
            .withIssuer("taskly")
            .sign(algorithm)

        val newRefreshToken = generateRefreshTokenInTx(ctx, userId)
        return AuthResponse(accessToken, newRefreshToken, "$userId")
    }

    private fun revokeAll(ctx: org.jooq.DSLContext, userId: UUID) {
        ctx.update(RefreshTokensTable.TABLE)
            .set(RefreshTokensTable.REVOKED, true)
            .where(RefreshTokensTable.USER_ID.eq(userId))
            .execute()
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

    const val MIN_PASSWORD = 8

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}