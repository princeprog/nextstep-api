package com.nextstep.api.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SmtpEmailSenderTest {

    @Test
    void sendsOnlyToABareAddress() throws Exception {
        assertThat(SmtpEmailSender.bareAddress("ana@example.com").getAddress()).isEqualTo("ana@example.com");
        for (String recipient : new String[] {"Call-555-0100 <victim@corp.example>", "(7)victim@corp.example"}) {
            assertThatThrownBy(() -> SmtpEmailSender.bareAddress(recipient)).as(recipient)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
