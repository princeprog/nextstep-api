package com.nextstep.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.nextstep.api.support.IntegrationTestSupport;
import com.nextstep.api.support.TestBrowser;
import com.nextstep.api.support.TestBrowser.Response;

/**
 * Sessions are stored with Java serialization. After an upgrade changes a stored class, old sessions can't be read;
 * the user must simply be signed out, not see server errors until the session expires.
 */
class SessionResilienceTest extends IntegrationTestSupport {

    @Test
    void unreadableStoredSessionSignsTheUserOut() {
        TestBrowser b = signedInUser();
        String accountId = b.get("/api/v1/me").text("id");
        // A Java serialization stream that ends inside a class descriptor, like data written by an incompatible version.
        byte[] unreadable = {(byte) 0xAC, (byte) 0xED, 0x00, 0x05, 0x73, 0x72};
        int updated = jdbc.update("""
                UPDATE spring_session_attributes SET attribute_bytes = ?
                WHERE attribute_name = 'SPRING_SECURITY_CONTEXT'
                  AND session_primary_id IN (SELECT primary_id FROM spring_session WHERE principal_name = ?)
                """, unreadable, accountId);
        assertThat(updated).isEqualTo(1);

        Response me = b.get("/api/v1/me");
        assertThat(me.status()).as(me.body()).isEqualTo(401);
        assertThat(me.code()).isEqualTo("UNAUTHENTICATED");

        b.refreshCsrf();
        assertThat(b.post("/api/v1/auth/login", TestBrowser.map("email", "nobody@example.com", "password", "irrelevant-password"))
                .status()).as("the browser can sign in again").isEqualTo(401);
    }
}
