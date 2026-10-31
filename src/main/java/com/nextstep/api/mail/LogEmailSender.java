package com.nextstep.api.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local development only (MAIL_MODE=log): writes each email, including its link, to the log instead of sending it.
 * The production profile refuses to start in this mode.
 */
public final class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.info("MAIL_MODE=log, so this email was not sent.\nTo: {}\nSubject: {}\n\n{}",
                message.to(), message.subject(), message.text());
    }
}
