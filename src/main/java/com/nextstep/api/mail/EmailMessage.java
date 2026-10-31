package com.nextstep.api.mail;

/** One email: a plain-text body and an equivalent HTML body. */
public record EmailMessage(String to, String subject, String text, String html) {
}
