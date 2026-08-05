package cn.gzuoj.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import java.nio.file.Path

/** 验证邮件投递开关和显式发件人不会被账户流程绕过。 */
class AccountMailServiceTest {
    @Test
    fun `sends verification message with configured sender`() {
        val sender = mock(JavaMailSender::class.java)
        val service = AccountMailService(sender, appProperties(mailDeliveryEnabled = true, mailFrom = "sender@example.com"))

        service.sendVerification("receiver@example.com", "123456")

        val messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
        verify(sender).send(messageCaptor.capture())
        val message = messageCaptor.value
        assertEquals("sender@example.com", message.from)
        assertEquals(listOf("receiver@example.com"), message.to?.toList())
        assertEquals("GZU OJ 邮箱验证", message.subject)
    }

    @Test
    fun `does not call smtp sender when delivery is disabled`() {
        val sender = mock(JavaMailSender::class.java)
        val service = AccountMailService(sender, appProperties(mailDeliveryEnabled = false))

        service.sendVerification("receiver@example.com", "123456")

        verifyNoInteractions(sender)
    }

    /** 构造只包含邮件测试所需参数的应用配置。 */
    private fun appProperties(mailDeliveryEnabled: Boolean, mailFrom: String = "") = AppProperties(
        publicBaseUrl = "http://localhost:8080",
        artifactRoot = Path.of("artifacts"),
        mailDeliveryEnabled = mailDeliveryEnabled,
        mailFrom = mailFrom,
    )
}
