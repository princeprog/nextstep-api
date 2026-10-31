package com.nextstep.api.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import com.nextstep.api.common.NextStepProperties;

@Configuration(proxyBeanMethods = false)
class MailConfig {

    @Bean
    EmailSender emailSender(NextStepProperties properties, ObjectProvider<JavaMailSender> mailSender) {
        String mode = properties.getMail().getMode();
        return switch (mode == null ? "" : mode) {
            case "smtp" -> new SmtpEmailSender(mailSender.getObject(), properties.getMail().getFrom());
            case "log" -> new LogEmailSender();
            default -> throw new IllegalStateException("MAIL_MODE must be log or smtp");
        };
    }
}
