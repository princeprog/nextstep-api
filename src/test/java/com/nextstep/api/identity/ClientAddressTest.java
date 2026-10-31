package com.nextstep.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientAddressTest {

    @Test
    void ipv4AddressesAreKeptAsTheyAre() {
        assertThat(ClientAddress.group("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv6ClientsShareOneLimitPerSlash64() {
        String one = ClientAddress.group("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        String other = ClientAddress.group("2001:db8:1:2::1");
        assertThat(one).isEqualTo(other).endsWith("/64");
        assertThat(ClientAddress.group("2001:db8:1:3::1")).isNotEqualTo(one);
        assertThat(ClientAddress.group("[2001:db8:1:2::9]")).isEqualTo(one);
    }

    @Test
    void ipv4MappedAddressesCountAsTheIpv4Client() {
        assertThat(ClientAddress.group("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void missingOrUnreadableAddressesStillGetAKey() {
        assertThat(ClientAddress.group(null)).isEqualTo("unknown");
        assertThat(ClientAddress.group("not:an:address")).isEqualTo("not:an:address");
    }
}
