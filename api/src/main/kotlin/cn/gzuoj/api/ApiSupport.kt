package cn.gzuoj.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat

/** API 错误的稳定响应结构。 */
data class ApiError(
    /** 机器可读错误码。 */
    val code: String,
    /** 面向用户的中文错误信息。 */
    val message: String,
    /** 错误发生时间。 */
    val timestamp: Instant = Instant.now(),
)

/** 带 HTTP 状态和业务错误码的受控异常。 */
class ApiException(
    /** HTTP 响应状态。 */
    val status: HttpStatus,
    /** 机器可读错误码。 */
    val code: String,
    /** 面向用户的中文错误信息。 */
    override val message: String,
) : RuntimeException(message)

/** 将服务异常统一转换为不会泄露内部信息的 JSON。 */
@RestControllerAdvice
class ApiExceptionHandler {
    /** 处理受控业务异常。 */
    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ResponseEntity<ApiError> =
        ResponseEntity.status(exception.status).body(ApiError(exception.code, exception.message))

    /** 处理请求字段校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(exception: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val message = exception.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "请求参数不合法"
        return ResponseEntity.badRequest().body(ApiError("VALIDATION_FAILED", message))
    }
}

/** 密码学哈希、令牌和常量时间比较工具。 */
object SecureValues {
    /** 系统安全随机数源。 */
    private val random = SecureRandom()

    /** 计算 UTF-8 文本的 SHA-256 十六进制值。 */
    fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    /** 计算字节内容的 SHA-256 十六进制值。 */
    fun sha256(value: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))

    /** 生成包含指定随机字节数的 URL 安全令牌。 */
    fun randomToken(bytes: Int = 32): String {
        val value = ByteArray(bytes)
        random.nextBytes(value)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }

    /** 生成固定六位数字验证码。 */
    fun verificationCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    /** 使用常量时间比较两个十六进制哈希。 */
    fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.US_ASCII), right.toByteArray(Charsets.US_ASCII))
}
