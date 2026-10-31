package com.nextstep.api.mail;

import java.nio.charset.StandardCharsets;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/** Sends through any provider's SMTP service (MAIL_MODE=smtp, configured with MAIL_HOST and related settings). */
public final class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        MimeMessage mime = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(bareAddress(message.to()));
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
        } catch (MessagingException ex) {
            throw new IllegalStateException("Could not build the email", ex);
        }
        mailSender.send(mime);
    }

    /** The recipient as a single bare address. A display name or comment would put someone else's text in the header. */
    static InternetAddress bareAddress(String to) {
        try {
            InternetAddress address = new InternetAddress(to, true);
            if (address.getPersonal() == null && to.equals(address.getAddress())) {
                return address;
            }
        } catch (AddressException ex) {
            // Refused below, like any other address that isn't bare.
        }
        throw new IllegalArgumentException("The recipient must be a bare email address");
    }
}
