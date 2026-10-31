package com.nextstep.api.identity;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The client address used for per-address rate limits, after trusted proxy headers have been applied by the servlet
 * container. An IPv6 client usually controls a whole /64 network, so all addresses in one /64 share a limit.
 */
final class ClientAddress {

    private ClientAddress() {
    }

    static String of(HttpServletRequest request) {
        return group(request.getRemoteAddr());
    }

    static String group(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        if (address.indexOf(':') < 0) {
            return address;
        }
        try {
            InetAddress parsed = InetAddress.ofLiteral(address.startsWith("[") && address.endsWith("]")
                    ? address.substring(1, address.length() - 1) : address);
            if (!(parsed instanceof Inet6Address)) {
                // An IPv4-mapped IPv6 address is the IPv4 client itself.
                return parsed.getHostAddress();
            }
            byte[] network = Arrays.copyOf(parsed.getAddress(), 16);
            Arrays.fill(network, 8, 16, (byte) 0);
            return InetAddress.getByAddress(network).getHostAddress() + "/64";
        } catch (IllegalArgumentException | UnknownHostException ex) {
            return address;
        }
    }
}
