package com.nextstep.api.support;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nextstep.api.mail.EmailMessage;
import com.nextstep.api.mail.EmailSender;

/** Captures outgoing email in tests, and can make delivery to chosen addresses fail. */
public class TestMailbox implements EmailSender {

    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]{43})");

    private final List<EmailMessage> inbox = new CopyOnWriteArrayList<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    @Override
    public void send(EmailMessage message) {
        if (failing.contains(message.to())) {
            throw new IllegalStateException("Simulated SMTP failure");
        }
        inbox.add(message);
    }

    public void failFor(String recipient) {
        failing.add(recipient);
    }

    public void recover(String recipient) {
        failing.remove(recipient);
    }

    /** Waits for an email to the recipient whose subject contains the text, and takes it out of the inbox. */
    public EmailMessage await(String recipient, String subjectContains) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<EmailMessage> found = inbox.stream()
                    .filter(m -> m.to().equals(recipient) && m.subject().contains(subjectContains))
                    .findFirst();
            if (found.isPresent()) {
                inbox.remove(found.get());
                return found.get();
            }
            pause(20);
        }
        throw new AssertionError("No email with \"" + subjectContains + "\" reached " + recipient);
    }

    /** Fails if any email reaches the recipient within the wait. */
    public void assertNothingFor(String recipient, Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            if (inbox.stream().anyMatch(m -> m.to().equals(recipient))) {
                throw new AssertionError("Unexpected email to " + recipient);
            }
            pause(20);
        }
    }

    public static String token(EmailMessage message) {
        Matcher matcher = TOKEN.matcher(message.text());
        if (!matcher.find()) {
            throw new AssertionError("No link token in: " + message.text());
        }
        return matcher.group(1);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
