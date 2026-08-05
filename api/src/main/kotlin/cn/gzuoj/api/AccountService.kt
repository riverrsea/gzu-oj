package cn.gzuoj.api

import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 注册、验证和密码找回的数据库事务服务。 */
@Service
class AccountService(
    /** JDBC 数据访问入口。 */
    private val jdbc: JdbcTemplate,
    /** Argon2id 密码编码器。 */
    private val passwordEncoder: PasswordEncoder,
    /** 账户邮件服务。 */
    private val mailService: AccountMailService,
    /** 应用配置。 */
    private val properties: AppProperties,
) {
    /** 创建未验证账号并发送一次性邮箱验证码。 */
    @Transactional
    fun register(request: RegisterRequest) {
        if (!properties.registrationEnabled) {
            throw ApiException(HttpStatus.FORBIDDEN, "REGISTRATION_DISABLED", "当前未开放注册")
        }
        val userId = UUID.randomUUID()
        val email = request.email.trim().lowercase()
        try {
            jdbc.update(
                "INSERT INTO app_user(id, username, email, password_hash) VALUES (?, ?, ?, ?)",
                userId,
                request.username.trim(),
                email,
                passwordEncoder.encode(request.password),
            )
        } catch (_: DuplicateKeyException) {
            throw ApiException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS", "用户名或邮箱已被使用")
        }
        val code = issueVerificationCode(userId)
        mailService.sendVerification(email, code)
    }

    /** 核验邮箱验证码并启用登录。 */
    @Transactional
    fun verifyEmail(request: VerifyEmailRequest) {
        val record = jdbc.query(
            """
            SELECT ev.id, ev.code_hash
            FROM email_verification ev
            JOIN app_user u ON u.id = ev.user_id
            WHERE lower(u.email) = lower(?) AND ev.consumed_at IS NULL AND ev.expires_at > now()
            ORDER BY ev.created_at DESC LIMIT 1 FOR UPDATE
            """.trimIndent(),
            { result, _ -> result.getObject("id", UUID::class.java) to result.getString("code_hash") },
            request.email.trim(),
        ).firstOrNull() ?: throw ApiException(
            HttpStatus.BAD_REQUEST,
            "VERIFICATION_INVALID",
            "邮箱验证码无效或已过期",
        )
        if (!SecureValues.constantTimeEquals(record.second, SecureValues.sha256(request.code))) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VERIFICATION_INVALID", "邮箱验证码无效或已过期")
        }
        jdbc.update("UPDATE email_verification SET consumed_at = now() WHERE id = ?", record.first)
        jdbc.update(
            "UPDATE app_user SET email_verified = TRUE, updated_at = now() WHERE lower(email) = lower(?)",
            request.email.trim(),
        )
    }

    /** 请求密码重置；无论账号是否存在都返回相同结果。 */
    @Transactional
    fun requestPasswordReset(email: String) {
        val userId = jdbc.query(
            "SELECT id FROM app_user WHERE lower(email) = lower(?) AND email_verified = TRUE AND enabled = TRUE",
            { result, _ -> result.getObject("id", UUID::class.java) },
            email.trim(),
        ).firstOrNull() ?: return
        val token = SecureValues.randomToken()
        jdbc.update(
            "INSERT INTO password_reset(id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(),
            userId,
            SecureValues.sha256(token),
            Timestamp.from(Instant.now().plus(30, ChronoUnit.MINUTES)),
        )
        mailService.sendPasswordReset(email.trim().lowercase(), token)
    }

    /** 消费重置令牌、更新密码并注销该用户的既有会话。 */
    @Transactional
    fun confirmPasswordReset(request: PasswordResetConfirmRequest) {
        val reset = jdbc.query(
            """
            SELECT id, user_id FROM password_reset
            WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > now()
            FOR UPDATE
            """.trimIndent(),
            { result, _ ->
                result.getObject("id", UUID::class.java) to result.getObject("user_id", UUID::class.java)
            },
            SecureValues.sha256(request.token),
        ).firstOrNull() ?: throw ApiException(
            HttpStatus.BAD_REQUEST,
            "RESET_TOKEN_INVALID",
            "重置链接无效或已过期",
        )
        jdbc.update(
            "UPDATE app_user SET password_hash = ?, updated_at = now() WHERE id = ?",
            passwordEncoder.encode(request.newPassword),
            reset.second,
        )
        jdbc.update("UPDATE password_reset SET consumed_at = now() WHERE id = ?", reset.first)
        jdbc.update(
            "DELETE FROM spring_session WHERE principal_name = (SELECT username FROM app_user WHERE id = ?)",
            reset.second,
        )
    }

    /** 创建新邮箱验证码并使该用户旧验证码失效。 */
    private fun issueVerificationCode(userId: UUID): String {
        jdbc.update(
            "UPDATE email_verification SET consumed_at = now() WHERE user_id = ? AND consumed_at IS NULL",
            userId,
        )
        val code = SecureValues.verificationCode()
        jdbc.update(
            "INSERT INTO email_verification(id, user_id, code_hash, expires_at) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(),
            userId,
            SecureValues.sha256(code),
            Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES)),
        )
        return code
    }
}
