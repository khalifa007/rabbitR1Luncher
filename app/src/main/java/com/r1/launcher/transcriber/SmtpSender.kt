package com.r1.launcher.transcriber

import java.io.File
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Plain-old JavaMail SMTP send. Targets Gmail's submission endpoint by
 * default (`smtp.gmail.com:587` STARTTLS) but takes any host/port from
 * [TranscriberPrefs].
 *
 * Always called from a background thread — [send] blocks for the duration
 * of the SMTP handshake + upload (a 30 MB attachment over 4G can take a
 * minute). Caller is responsible for executor isolation.
 *
 * Gmail-specific gotcha: the user's regular Google account password is
 * rejected by SMTP. They need a 16-character "app password" generated at
 * https://myaccount.google.com/apppasswords (requires 2FA enabled on the
 * Google account).
 *
 * Two send paths share the SMTP machinery:
 *   - [send] (meeting-typed) — used by the Meetings/Transcriber feature.
 *   - [sendGeneric] (EmailPayload) — generic path for arbitrary attachments.
 */
class SmtpSender(private val prefs: TranscriberPrefs) {

    sealed class Result {
        data object Success : Result()
        data class Failure(val message: String) : Result()
    }

    /** An attachment carried by [EmailPayload]. Files are streamed through
     *  JavaMail's [FileDataSource]; inline strings get a text/plain MIME part. */
    sealed class Attachment {
        abstract val filename: String
        data class FileAttachment(
            override val filename: String,
            val file: File,
            val contentType: String = "application/octet-stream",
        ) : Attachment()
        data class TextAttachment(
            override val filename: String,
            val text: String,
            val mime: String = "text/plain; charset=utf-8",
        ) : Attachment()
    }

    data class EmailPayload(
        val recipient: String,
        val subject: String,
        val body: String,
        val attachments: List<Attachment> = emptyList(),
    )

    /** Meeting-typed helper preserved for the existing Transcriber call site. */
    fun send(
        meeting: Meeting,
        recipient: String,
        audioFile: File,
        transcriptText: String,
    ): Result {
        val atts = buildList<Attachment> {
            if (audioFile.exists() && audioFile.length() > 0) {
                add(Attachment.FileAttachment(
                    filename = "${meeting.uuid}.m4a",
                    file = audioFile,
                    contentType = "audio/mp4",
                ))
            }
            if (transcriptText.isNotBlank()) {
                add(Attachment.TextAttachment(
                    filename = "${meeting.uuid}.txt",
                    text = transcriptText,
                ))
            }
        }
        return sendGeneric(EmailPayload(
            recipient = recipient,
            subject = "Meeting recording — ${meeting.title}",
            body = buildMeetingBody(meeting, transcriptText),
            attachments = atts,
        ))
    }

    /**
     * Register JavaMail's JAF content handlers programmatically. The APK's
     * packaging step strips `META-INF/mailcap.default` (it's a duplicate
     * between android-mail and android-activation), and R8 in release builds
     * can further obscure resource-based handler discovery — so `DataHandler`
     * would fail with "no object DCH for MIME type" when building a multipart
     * message, breaking attachment send ONLY in the minified release build.
     * Wiring the handlers by class name makes it work regardless of which
     * META-INF resources survive. Idempotent; cheap to call per send.
     */
    private fun ensureMailcap() {
        runCatching {
            val mc = javax.activation.MailcapCommandMap()
            mc.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain")
            mc.addMailcap("text/html;; x-java-content-handler=com.sun.mail.handlers.text_html")
            mc.addMailcap("text/xml;; x-java-content-handler=com.sun.mail.handlers.text_xml")
            mc.addMailcap("multipart/*;; x-java-content-handler=com.sun.mail.handlers.multipart_mixed")
            mc.addMailcap("message/rfc822;; x-java-content-handler=com.sun.mail.handlers.message_rfc822")
            javax.activation.CommandMap.setDefaultCommandMap(mc)
        }
    }

    /** Generic SMTP send for arbitrary attachments. */
    fun sendGeneric(payload: EmailPayload): Result {
        ensureMailcap()
        val user = prefs.smtpUser ?: return Result.Failure("SMTP user not configured")
        val pass = prefs.smtpPassword ?: return Result.Failure("SMTP password not configured")
        val host = prefs.smtpHost
        val port = prefs.smtpPort
        if (payload.recipient.isBlank()) return Result.Failure("recipient is empty")

        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            // NOTE: deliberately NOT setting `mail.smtp.ssl.trust = host`. That
            // disabled CA validation for the SMTP host, so a MITM presenting any
            // cert for smtp.gmail.com could intercept the app password during
            // STARTTLS. Standard providers (Gmail/Outlook/etc.) chain to a
            // public CA and validate normally. A self-hosted server with a
            // self-signed cert must install a CA-valid cert.
            // Without these, a flaky link blocks the send executor forever.
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "60000")
            put("mail.smtp.writetimeout", "120000")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(user, pass)
        })

        return try {
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(user))
                setRecipients(
                    Message.RecipientType.TO,
                    payload.recipient.split(',', ';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { InternetAddress(it) }
                        .toTypedArray(),
                )
                subject = payload.subject
                sentDate = Date()
            }

            val multipart = MimeMultipart()

            val bodyPart = MimeBodyPart()
            bodyPart.setText(payload.body)
            multipart.addBodyPart(bodyPart)

            payload.attachments.forEach { att ->
                val part = MimeBodyPart()
                when (att) {
                    is Attachment.FileAttachment -> {
                        if (att.file.exists() && att.file.length() > 0) {
                            part.dataHandler = DataHandler(FileDataSource(att.file))
                            part.fileName = att.filename
                            multipart.addBodyPart(part)
                        }
                    }
                    is Attachment.TextAttachment -> {
                        if (att.text.isNotBlank()) {
                            part.setContent(att.text, att.mime)
                            part.fileName = att.filename
                            multipart.addBodyPart(part)
                        }
                    }
                }
            }

            msg.setContent(multipart)
            Transport.send(msg)
            Result.Success
        } catch (t: Throwable) {
            android.util.Log.e("SmtpSender", "send failed", t)
            Result.Failure(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun buildMeetingBody(meeting: Meeting, transcript: String): String {
        val durationSec = (meeting.durationMs / 1000L).toInt()
        val mins = durationSec / 60
        val secs = durationSec % 60
        val preview = transcript.lineSequence().take(6).joinToString("\n")
        return buildString {
            appendLine("Meeting recording from your R1.")
            appendLine()
            appendLine("Title:    ${meeting.title}")
            appendLine("Duration: ${"%d:%02d".format(mins, secs)}")
            appendLine("Speakers: ${meeting.speakerCount}")
            meeting.languageCode?.let { appendLine("Language: $it") }
            appendLine()
            if (preview.isNotBlank()) {
                appendLine("--- Transcript preview ---")
                appendLine(preview)
                if (transcript.lines().size > 6) appendLine("…")
                appendLine()
            }
            appendLine("Full transcript and audio attached.")
        }
    }
}
