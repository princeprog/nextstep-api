package com.nextstep.api.mail;

/** Delivers one email or throws, so the outbox can retry later. */
public interface EmailSender {

    void send(EmailMessage message);
}
