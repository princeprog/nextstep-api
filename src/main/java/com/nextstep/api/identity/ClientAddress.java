package com.nextstep.api.identity;

import jakarta.servlet.http.HttpServletRequest;

/** The client address after trusted proxy headers have been applied by the servlet container. */
final class ClientAddress {

    private ClientAddress() {
    }

    static String of(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null ? "unknown" : address;
    }
}

