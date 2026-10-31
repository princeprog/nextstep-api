package com.nextstep.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailAddressRulesTest {

    @Test
    void acceptsOrdinaryAddresses() {
        for (String address : new String[] {"ana@example.com", "first.last+jobs@mail.example.co", "o'neil@example.ph",
                "a_b-c@sub-domain.example.org"}) {
            assertThat(Validators.isEmail(address)).as(address).isTrue();
        }
    }

    @Test
    void refusesNamesCommentsAndOtherTextAroundTheAddress() {
        for (String address : new String[] {"Call-555-0100<victim@corp.example>", "(7)victim@corp.example",
                "\"quoted\"@example.com", "victim@corp.example, other@corp.example", "two words@example.com",
                "a..b@example.com", ".a@example.com", "a.@example.com", "user@localhost", "user@-example.com",
                "x".repeat(65) + "@example.com"}) {
            assertThat(Validators.isEmail(address)).as(address).isFalse();
        }
    }
}
