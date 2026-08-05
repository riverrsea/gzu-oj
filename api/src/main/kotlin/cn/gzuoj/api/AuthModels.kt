package cn.gzuoj.api

import jakarta.servlet.http.HttpSession
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 注册请求。 */
data class RegisterRequest(
    /** 由字母开头、包含字母数字或下划线的用户名。 */
    @field:Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{2,31}$", message = "用户名需以字母开头，长度为 3 到 32 位")
    val username: String,
    /** 用户邮箱。 */
    @field:Email(message = "邮箱格式不正确")
    @field:Size(max = 320, message = "邮箱过长")
    val email: String,
    /** 账号密码。 */
    @field:Size(min = 10, max = 128, message = "密码长度需为 10 到 128 位")
    val password: String,
    /** 图形验证码。 */
    @field:NotBlank(message = "请输入图形验证码")
    val captcha: String,
)

/** 邮箱验证码确认请求。 */
data class VerifyEmailRequest(
    /** 待验证邮箱。 */
    @field:Email(message = "邮箱格式不正确")
    val email: String,
    /** 六位邮箱验证码。 */
    @field:Pattern(regexp = "^[0-9]{6}$", message = "邮箱验证码必须为六位数字")
    val code: String,
)

/** JSON 会话登录请求。 */
data class LoginRequest(
    /** 用户名或邮箱。 */
    @field:NotBlank(message = "请输入用户名或邮箱")
    val identity: String,
    /** 账号密码。 */
    @field:NotBlank(message = "请输入密码")
    val password: String,
    /** 图形验证码。 */
    @field:NotBlank(message = "请输入图形验证码")
    val captcha: String,
)

/** 找回密码邮件请求。 */
data class PasswordResetRequest(
    /** 注册邮箱。 */
    @field:Email(message = "邮箱格式不正确")
    val email: String,
    /** 图形验证码。 */
    @field:NotBlank(message = "请输入图形验证码")
    val captcha: String,
)

/** 使用一次性令牌设置新密码的请求。 */
data class PasswordResetConfirmRequest(
    /** 邮件中的一次性高强度令牌。 */
    @field:NotBlank(message = "重置令牌不能为空")
    val token: String,
    /** 新密码。 */
    @field:Size(min = 10, max = 128, message = "密码长度需为 10 到 128 位")
    val newPassword: String,
)

/** 当前用户的安全公开信息。 */
data class CurrentUserResponse(
    /** 用户标识。 */
    val id: UUID,
    /** 用户名。 */
    val username: String,
    /** 用户角色。 */
    val role: String,
)

/** 无敏感信息的认证操作结果。 */
data class AuthMessageResponse(
    /** 中文结果说明。 */
    val message: String,
)

/** 图形验证码创建与一次性校验。 */
@Service
class CaptchaService {
    /** 创建验证码并保存到当前服务端会话。 */
    fun create(session: HttpSession): String {
        val code = SecureValues.randomToken(5)
            .filter { it.isLetterOrDigit() }
            .take(5)
            .uppercase()
            .padEnd(5, '7')
        session.setAttribute(CAPTCHA_HASH, SecureValues.sha256(code))
        session.setAttribute(CAPTCHA_EXPIRES_AT, Instant.now().plus(5, ChronoUnit.MINUTES).toEpochMilli())
        return renderSvg(code)
    }

    /** 校验并消费验证码，防止重放。 */
    fun verify(session: HttpSession, candidate: String) {
        val expectedHash = session.getAttribute(CAPTCHA_HASH) as? String
        val expiresAt = session.getAttribute(CAPTCHA_EXPIRES_AT) as? Long
        session.removeAttribute(CAPTCHA_HASH)
        session.removeAttribute(CAPTCHA_EXPIRES_AT)
        if (expectedHash == null || expiresAt == null || expiresAt < Instant.now().toEpochMilli()) {
            throw ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "CAPTCHA_EXPIRED", "图形验证码已过期，请刷新后重试")
        }
        if (!SecureValues.constantTimeEquals(expectedHash, SecureValues.sha256(candidate.trim().uppercase()))) {
            throw ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "CAPTCHA_INVALID", "图形验证码错误")
        }
    }

    /** 渲染不依赖外部字体资源的 SVG 验证码。 */
    private fun renderSvg(code: String): String {
        val glyphs = code.mapIndexed { index, char ->
            val x = 18 + index * 24
            val rotate = (index % 3 - 1) * 8
            """<text x="$x" y="35" transform="rotate($rotate $x 35)">$char</text>"""
        }.joinToString("")
        return """<svg xmlns="http://www.w3.org/2000/svg" width="140" height="48" viewBox="0 0 140 48"><rect width="140" height="48" fill="#f4f0e8"/><path d="M3 30 L137 13 M4 15 L136 39" stroke="#9a8f80" stroke-width="1"/><g fill="#1e2927" font-family="monospace" font-size="27" font-weight="700">$glyphs</g></svg>"""
    }

    /** 会话验证码属性名。 */
    private companion object {
        const val CAPTCHA_HASH = "gzu-oj.captcha.hash"
        const val CAPTCHA_EXPIRES_AT = "gzu-oj.captcha.expires-at"
    }
}

/** 验证邮件与密码重置邮件投递器。 */
@Service
class AccountMailService(
    /** Spring 邮件发送器。 */
    private val mailSender: JavaMailSender,
    /** 应用配置。 */
    private val properties: AppProperties,
) {
    /** 服务日志；关闭真实投递时记录本地开发凭据。 */
    private val log = LoggerFactory.getLogger(javaClass)

    /** 发送六位邮箱验证码。 */
    fun sendVerification(email: String, code: String) {
        deliver(email, "GZU OJ 邮箱验证", "你的邮箱验证码是：$code，10 分钟内有效。")
    }

    /** 发送密码重置链接。 */
    fun sendPasswordReset(email: String, token: String) {
        val link = "${properties.publicBaseUrl}/reset-password?token=$token"
        deliver(email, "GZU OJ 重置密码", "请在 30 分钟内使用以下链接重置密码：$link")
    }

    /** 根据配置投递邮件或仅写本地日志。 */
    private fun deliver(email: String, subject: String, content: String) {
        if (!properties.mailDeliveryEnabled) {
            log.info("本地邮件投递已关闭，收件人={}，主题={}，内容={}", email, subject, content)
            return
        }
        mailSender.send(SimpleMailMessage().also {
            it.setTo(email)
            it.subject = subject
            it.text = content
        })
    }
}
