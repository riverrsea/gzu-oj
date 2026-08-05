package cn.gzuoj.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 当前已认证用户在服务端会话中的最小投影。 */
data class AppPrincipal(
    /** 用户数据库标识。 */
    val userId: UUID,
    /** 用户名。 */
    private val loginName: String,
    /** Argon2id 密码哈希。 */
    private val encodedPassword: String,
    /** 用户角色。 */
    val role: String,
    /** 账号是否启用。 */
    private val active: Boolean,
) : UserDetails {
    /** 返回角色权限。 */
    override fun getAuthorities(): Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_$role"))

    /** 返回密码哈希。 */
    override fun getPassword(): String = encodedPassword

    /** 返回稳定用户名。 */
    override fun getUsername(): String = loginName

    /** 返回账号是否启用。 */
    override fun isEnabled(): Boolean = active
}

/** Spring Security 与服务端会话配置。 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {
    /** 配置 Argon2id 密码编码器。 */
    @Bean
    fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

    /** 支持使用用户名或邮箱加载已经验证的用户。 */
    @Bean
    fun userDetailsService(jdbc: JdbcTemplate): UserDetailsService = UserDetailsService { identity ->
        val users = jdbc.query(
            """
            SELECT id, username, password_hash, role, enabled
            FROM app_user
            WHERE (lower(username) = lower(?) OR lower(email) = lower(?))
              AND email_verified = TRUE
            """.trimIndent(),
            { result, _ ->
                AppPrincipal(
                    userId = result.getObject("id", UUID::class.java),
                    loginName = result.getString("username"),
                    encodedPassword = result.getString("password_hash"),
                    role = result.getString("role"),
                    active = result.getBoolean("enabled"),
                )
            },
            identity,
            identity,
        )
        users.firstOrNull() ?: throw UsernameNotFoundException("账号不存在或邮箱未验证")
    }

    /** 配置基于数据库用户的认证提供器。 */
    @Bean
    fun authenticationProvider(
        userDetailsService: UserDetailsService,
        passwordEncoder: PasswordEncoder,
    ): DaoAuthenticationProvider = DaoAuthenticationProvider(userDetailsService).also {
        it.setPasswordEncoder(passwordEncoder)
    }

    /** 暴露给 JSON 登录控制器使用的认证管理器。 */
    @Bean
    fun authenticationManager(configuration: AuthenticationConfiguration): AuthenticationManager =
        configuration.authenticationManager

    /** 配置接口授权、会话、CSRF Cookie 和安全响应头。 */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse().also {
            it.setCookieName("XSRF-TOKEN")
            it.setHeaderName("X-XSRF-TOKEN")
            it.setCookiePath("/")
        }
        // 同源 JSON 客户端通过响应体取得令牌并放入请求头，统一使用未掩码的原始令牌契约。
        val csrfRequestHandler = CsrfTokenRequestAttributeHandler()

        http
            .csrf {
                it.csrfTokenRepository(csrfRepository)
                    .csrfTokenRequestHandler(csrfRequestHandler)
                    .ignoringRequestMatchers("/internal/worker/v1/**")
            }
            .authorizeHttpRequests {
                it.requestMatchers(
                    "/api/v1/auth/**",
                    "/api/v1/csrf",
                    "/api/v1/problems/**",
                    "/api/v1/shares/**",
                    "/v3/api-docs/**",
                    "/internal/worker/v1/**",
                    "/actuator/health/**",
                ).permitAll()
                it.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                it.anyRequest().authenticated()
            }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout {
                it.logoutUrl("/api/v1/auth/logout")
                    .deleteCookies("GZUOJSESSION")
                    .logoutSuccessHandler { _, response, _ -> response.status = 204 }
            }
            .headers {
                it.contentSecurityPolicy { policy ->
                    policy.policyDirectives("default-src 'self'; frame-ancestors 'none'; object-src 'none'")
                }
            }
        return http.build()
    }
}

/** 前端初始化 CSRF Cookie 的接口。 */
@RestController
@RequestMapping("/api/v1")
class CsrfController {
    /** 返回 CSRF 令牌并触发 Cookie 写入。 */
    @GetMapping("/csrf")
    fun csrf(request: HttpServletRequest): Map<String, String> {
        val token = request.getAttribute(CsrfToken::class.java.name) as CsrfToken
        return mapOf("headerName" to token.headerName, "token" to token.token)
    }
}
