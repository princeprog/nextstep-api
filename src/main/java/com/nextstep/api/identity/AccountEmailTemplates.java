package com.nextstep.api.identity;

import java.time.Duration;

import org.springframework.web.util.HtmlUtils;

import com.nextstep.api.mail.EmailMessage;

/**
 * Account emails. They never include text the user typed (such as a display name), so nobody can use NextStep to
 * send their own words to an address they don't own.
 */
final class AccountEmailTemplates {

    private AccountEmailTemplates() {
    }

    static EmailMessage verifyEmail(String to, String link, Duration validFor) {
        return message(to, "Confirm your email for NextStep",
                "Confirm this email address to finish creating your NextStep account.",
                "Confirm email", link,
                "This link works once and expires in " + describe(validFor) + ". If you didn't create an account, ignore "
                        + "this email; the account is removed if it isn't confirmed within a week.");
    }

    static EmailMessage accountExists(String to, String signInLink, String resetLink) {
        return message(to, "You already have a NextStep account",
                "Someone tried to create a NextStep account with this email address, but an account already exists.",
                "Sign in", signInLink,
                "If you forgot your password, reset it here: " + resetLink + ". If this wasn't you, you can ignore this "
                        + "email; nothing has changed.");
    }

    static EmailMessage resetPassword(String to, String link, Duration validFor) {
        return message(to, "Reset your NextStep password",
                "Use this link to choose a new password for your NextStep account.",
                "Choose a new password", link,
                "This link works once and expires in " + describe(validFor) + ". Resetting signs you out on every device. "
                        + "If you didn't ask for this, ignore this email; your password stays the same.");
    }

    static EmailMessage passwordChanged(String to, String resetLink) {
        return message(to, "Your NextStep password was changed",
                "The password for your NextStep account was just changed, and other devices were signed out.",
                "Reset your password", resetLink,
                "If you made this change, there's nothing else to do. If you didn't, reset your password now.");
    }

    static EmailMessage accountDeleted(String to) {
        return message(to, "Your NextStep account was deleted",
                "Your NextStep account and everything in it were deleted, and every session was signed out.",
                null, null,
                "Copies may remain in encrypted backups until they expire; if a backup is ever restored, the deletion is "
                        + "applied again. This is the last email you'll get from NextStep.");
    }

    private static EmailMessage message(String to, String subject, String intro, String action, String link, String closing) {
        StringBuilder text = new StringBuilder("Hello,\n\n").append(intro).append("\n\n");
        if (link != null) {
            text.append(action).append(": ").append(link).append("\n\n");
        }
        text.append(closing).append("\n\n— NextStep\n");

        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><body style=\"font-family:system-ui,sans-serif;"
                + "color:#1f2a24;line-height:1.5;max-width:36rem;margin:0 auto;padding:1.5rem\">");
        html.append("<p>Hello,</p><p>").append(HtmlUtils.htmlEscape(intro)).append("</p>");
        if (link != null) {
            String href = HtmlUtils.htmlEscape(link);
            html.append("<p><a href=\"").append(href).append("\" style=\"display:inline-block;padding:0.6rem 1.1rem;"
                    + "border-radius:999px;background:#1f5f4a;color:#ffffff;text-decoration:none;font-weight:600\">")
                    .append(HtmlUtils.htmlEscape(action)).append("</a></p>")
                    .append("<p style=\"font-size:0.9rem;color:#4a5a52\">Or open this link: <a href=\"").append(href).append("\">")
                    .append(href).append("</a></p>");
        }
        html.append("<p>").append(HtmlUtils.htmlEscape(closing)).append("</p><p>— NextStep</p></body></html>");
        return new EmailMessage(to, subject, text.toString(), html.toString());
    }

    private static String describe(Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes % (60 * 24) == 0) {
            long days = minutes / (60 * 24);
            return days == 1 ? "24 hours" : days + " days";
        }
        if (minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours == 1 ? "1 hour" : hours + " hours";
        }
        return minutes + " minutes";
    }
}
