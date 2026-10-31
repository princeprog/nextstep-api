package com.nextstep.api.identity;

import static com.nextstep.api.support.TestBrowser.map;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

class PasswordChangeTest extends IntegrationTestSupport {

    private static final String NEW_PASSWORD = "a-new-strong-passphrase";

    @Test
    void needsTheCurrentPasswordAndSignsOutOtherSessions() {
        String email = uniqueEmail();
        registerVerified(email, PASSWORD, "UTC");
        TestBrowser laptop = signIn(email, PASSWORD);
        TestBrowser phone = signIn(email, PASSWORD);

        Response wrong = laptop.post("/api/v1/me/password", map("currentPassword", "not-my-password-at-all", "newPassword", NEW_PASSWORD));
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(wrong.fieldError("currentPassword")).isNotBlank();
        Response same = laptop.post("/api/v1/me/password", map("currentPassword", PASSWORD, "newPassword", PASSWORD));
        assertThat(same.fieldError("newPassword")).isNotBlank();
        Response breached = laptop.post("/api/v1/me/password", map("currentPassword", PASSWORD, "newPassword", "a-breached-passphrase"));
        assertThat(breached.fieldError("newPassword")).contains("data breach");

        String before = laptop.sessionCookie();
        assertThat(laptop.post("/api/v1/me/password", map("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD)).status())
                .isEqualTo(204);
        assertThat(laptop.sessionCookie()).as("this browser gets a new session ID").isNotEqualTo(before);
        assertThat(laptop.get("/api/v1/me").status()).as("and stays signed in").isEqualTo(200);
        assertThat(phone.get("/api/v1/me").status()).as("other sessions end").isEqualTo(401);

        TestBrowser b = browser();
        b.refreshCsrf();
        assertThat(b.post("/api/v1/auth/login", map("email", email, "password", PASSWORD)).status()).isEqualTo(401);
        signIn(email, NEW_PASSWORD);
        assertThat(mailbox.await(email, "password was changed")).isNotNull();
    }

    @Test
    void repeatedWrongCurrentPasswordsAreLimited() {
        TestBrowser user = signedInUser();
        int last = 0;
        for (int i = 0; i < 6; i++) {
            last = user.post("/api/v1/me/password", map("currentPassword", "wrong-password-" + i, "newPassword", NEW_PASSWORD)).status();
        }
        assertThat(last).isEqualTo(429);
    }

    @Test
    void demoWorkspacesHaveNoPassword() {
        TestBrowser demo = browser();
        demo.refreshCsrf();
        assertThat(demo.post("/api/v1/auth/demo", null).status()).isEqualTo(201);
        demo.refreshCsrf();
        assertThat(demo.post("/api/v1/me/password", map("currentPassword", "x", "newPassword", NEW_PASSWORD)).code())
                .isEqualTo("DEMO_ACCOUNT");
        assertThat(demo.delete("/api/v1/me", map("password", "x")).code()).isEqualTo("DEMO_ACCOUNT");
    }
}
