package com.taskly.server.email

import com.taskly.server.SmtpConfig
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import java.util.Properties

data class Email(val to: String, val subject: String, val text: String)

fun interface EmailSender {
    fun send(email: Email)
}

/** Dev: emails only appear in the log (links included, so flows can be tried locally). */
class LogEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger("Email")
    override fun send(email: Email) {
        log.info("Email to {} — {}\n{}", email.to, email.subject, email.text)
    }
}

class SmtpEmailSender(private val cfg: SmtpConfig) : EmailSender {
    private val session: Session = Session.getInstance(
        Properties().apply {
            put("mail.smtp.host", cfg.host)
            put("mail.smtp.port", cfg.port.toString())
            put("mail.smtp.auth", (cfg.user.isNotBlank()).toString())
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
        },
        if (cfg.user.isBlank()) null else object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(cfg.user, cfg.password)
        },
    )

    override fun send(email: Email) {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(cfg.from))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(email.to))
            setSubject(email.subject, "UTF-8")
            setText(email.text, "UTF-8")
        }
        Transport.send(message)
    }
}

/** Process-wide sender; tests replace it with a capturing one. */
object Mailer {
    @Volatile var sender: EmailSender = LogEmailSender()

    fun init(cfg: SmtpConfig) {
        sender = if (cfg.host.isBlank()) LogEmailSender() else SmtpEmailSender(cfg)
    }

    fun send(email: Email) = sender.send(email)
}
