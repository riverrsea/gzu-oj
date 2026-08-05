package cn.gzuoj.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import jakarta.validation.Valid
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** JSON 认证 API。 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    /** 图形验证码服务。 */
    private val captchaService: CaptchaService,
    /** 账号业务服务。 */
    private val accountService: AccountService,
    /** Spring 认证管理器。 */
    private val authenticationManager: AuthenticationManager,
    /** 请求限流器。 */
    private val rateLimiter: RequestRateLimiter,
) {
    /** 返回新的 SVG 图形验证码并写入会话。 */
    @GetMapping("/captcha", produces = ["image/svg+xml"])
    fun captcha(session: HttpSession): String = captchaService.create(session)

    /** 注册未验证用户。 */
    @PostMapping("/register")
    fun register(
        @Valid @RequestBody body: RegisterRequest,
        request: HttpServletRequest,
        session: HttpSession,
    ): AuthMessageResponse {
        rateLimiter.check("register-ip:${request.remoteAddr}", 10, 3600)
        captchaService.verify(session, body.captcha)
        accountService.register(body)
        return AuthMessageResponse("注册成功，请查收邮箱验证码")
    }

    /** 验证注册邮箱。 */
    @PostMapping("/verify-email")
    fun verifyEmail(@Valid @RequestBody body: VerifyEmailRequest): AuthMessageResponse {
        rateLimiter.check("verify:${body.email.lowercase()}", 10, 600)
        accountService.verifyEmail(body)
        return AuthMessageResponse("邮箱验证成功")
    }

    /** 使用用户名或邮箱创建服务端登录会话。 */
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody body: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
        session: HttpSession,
    ): CurrentUserResponse {
        rateLimiter.check("login-ip:${request.remoteAddr}", 30, 600)
        rateLimiter.check("login-account:${body.identity.trim().lowercase()}", 10, 600)
        captchaService.verify(session, body.captcha)
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(body.identity.trim(), body.password),
        )
        request.changeSessionId()
        val context = SecurityContextHolder.createEmptyContext().also { it.authentication = authentication }
        SecurityContextHolder.setContext(context)
        HttpSessionSecurityContextRepository().saveContext(context, request, response)
        val principal = authentication.principal as AppPrincipal
        return CurrentUserResponse(principal.userId, principal.username, principal.role)
    }

    /** 获取当前登录用户。 */
    @GetMapping("/me")
    fun me(): CurrentUserResponse {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AppPrincipal
            ?: throw ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录")
        return CurrentUserResponse(principal.userId, principal.username, principal.role)
    }

    /** 发送找回密码邮件，响应不会泄露账号是否存在。 */
    @PostMapping("/password-reset/request")
    fun requestPasswordReset(
        @Valid @RequestBody body: PasswordResetRequest,
        request: HttpServletRequest,
        session: HttpSession,
    ): AuthMessageResponse {
        rateLimiter.check("reset-ip:${request.remoteAddr}", 10, 3600)
        captchaService.verify(session, body.captcha)
        accountService.requestPasswordReset(body.email)
        return AuthMessageResponse("若邮箱已注册，重置邮件将很快送达")
    }

    /** 使用邮件令牌设置新密码。 */
    @PostMapping("/password-reset/confirm")
    fun confirmPasswordReset(@Valid @RequestBody body: PasswordResetConfirmRequest): AuthMessageResponse {
        accountService.confirmPasswordReset(body)
        return AuthMessageResponse("密码已重置，请重新登录")
    }
}
