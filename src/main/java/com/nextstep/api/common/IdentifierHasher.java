package com.nextstep.api.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns client addresses and emails into keyed HMAC-SHA256 digests before they are stored, so rate-limit rows hold no
 * personal data that could be read back. Unkeyed hashes would not do: every IPv4 address can be hashed in minutes.
 */
@Component
public class IdentifierHasher {

    private static final Logger log = LoggerFactory.getLogger(IdentifierHasher.class);
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public IdentifierHasher(NextStepProperties properties) {
        String configured = properties.getSecurity().getIdentifierKey();
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            key = new SecretKeySpec(random, ALGORITHM);
            log.warn("IDENTIFIER_HASH_KEY is not set, so this instance uses a random key and its rate limits are not "
                    + "shared with other API instances.");
        } else {
            key = new SecretKeySpec(configured.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        }
    }

    /** A hex digest of the value, separated by purpose so the same email hashes differently per bucket. */
    public String hash(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
    }
}
