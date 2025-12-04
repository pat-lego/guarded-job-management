package com.adobe.aem.support.core.guards.token;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Produces tamper-proof orderable tokens based on creation time.
 * 
 * <p>Tokens created at time A will be ordered before tokens created at time B
 * when A < B. Any modification to the token will cause validation to fail,
 * making the orderability comparison invalid.</p>
 * 
 * <p>Token format: {@code timestamp.signature}</p>
 * 
 * <p>Usage:
 * <pre>{@code
 * GuardedOrderToken generator = new GuardedOrderToken("your-secret-key");
 * 
 * String token1 = generator.generate();
 * String token2 = generator.generate();
 * 
 * // Compare tokens (throws if tampered)
 * int result = generator.compare(token1, token2); // result < 0 since token1 was created first
 * 
 * // Validate a token
 * boolean valid = generator.isValid(token1);
 * }</pre>
 * </p>
 */
public class GuardedOrderToken {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DELIMITER = ".";
    
    private final byte[] secretKey;
    private final AtomicLong lastTimestamp = new AtomicLong(0);

    /**
     * Creates a new GuardedOrderToken generator with the specified secret key.
     *
     * @param secretKey the secret key used for HMAC signing (must not be null or empty)
     * @throws IllegalArgumentException if secretKey is null or empty
     */
    public GuardedOrderToken(String secretKey) {
        if (secretKey == null || secretKey.isEmpty()) {
            throw new IllegalArgumentException("Secret key must not be null or empty");
        }
        this.secretKey = secretKey.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates a new GuardedOrderToken generator with the specified secret key bytes.
     *
     * @param secretKey the secret key bytes used for HMAC signing (must not be null or empty)
     * @throws IllegalArgumentException if secretKey is null or empty
     */
    public GuardedOrderToken(byte[] secretKey) {
        if (secretKey == null || secretKey.length == 0) {
            throw new IllegalArgumentException("Secret key must not be null or empty");
        }
        this.secretKey = secretKey.clone();
    }

    /**
     * Generates a new orderable token based on the current time.
     * 
     * <p>Each call produces a strictly increasing value, even if called
     * within the same nanosecond.</p>
     *
     * @return a signed, orderable token string
     */
    public String generate() {
        long timestamp = getMonotonicTimestamp();
        String signature = sign(Long.toString(timestamp));
        return timestamp + DELIMITER + signature;
    }

    /**
     * Validates whether a token is authentic and has not been tampered with.
     *
     * @param token the token to validate
     * @return true if the token is valid and unmodified, false otherwise
     */
    public boolean isValid(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        
        String[] parts = token.split("\\" + DELIMITER, 2);
        if (parts.length != 2) {
            return false;
        }
        
        String timestamp = parts[0];
        String providedSignature = parts[1];
        
        try {
            Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        
        String expectedSignature = sign(timestamp);
        return constantTimeEquals(expectedSignature, providedSignature);
    }

    /**
     * Compares two tokens based on their creation time.
     * 
     * <p>Both tokens must be valid (unmodified). If either token has been
     * tampered with, this method throws an exception.</p>
     *
     * @param token1 the first token
     * @param token2 the second token
     * @return negative if token1 was created before token2,
     *         zero if created at the same time,
     *         positive if token1 was created after token2
     * @throws IllegalArgumentException if either token is invalid or tampered
     */
    public int compare(String token1, String token2) {
        if (!isValid(token1)) {
            throw new IllegalArgumentException("Token 1 is invalid or has been tampered with");
        }
        if (!isValid(token2)) {
            throw new IllegalArgumentException("Token 2 is invalid or has been tampered with");
        }
        
        long time1 = extractTimestamp(token1);
        long time2 = extractTimestamp(token2);
        
        return Long.compare(time1, time2);
    }

    /**
     * Extracts the timestamp from a valid token.
     * 
     * @param token the token to extract timestamp from
     * @return the timestamp value
     * @throws IllegalArgumentException if the token is invalid
     */
    public long extractTimestamp(String token) {
        if (!isValid(token)) {
            throw new IllegalArgumentException("Token is invalid or has been tampered with");
        }
        String[] parts = token.split("\\" + DELIMITER, 2);
        return Long.parseLong(parts[0]);
    }

    /**
     * Creates a comparator that can be used with sorting operations.
     * 
     * <p>The comparator throws IllegalArgumentException if it encounters
     * tampered tokens.</p>
     *
     * @return a comparator for token ordering
     */
    public java.util.Comparator<String> comparator() {
        return this::compare;
    }

    private long getMonotonicTimestamp() {
        long currentNanos = System.nanoTime();
        long currentMillis = System.currentTimeMillis();
        
        // Combine millis and nanos for a high-precision timestamp
        // Use millis as the base (for absolute time) and add nano offset
        long combined = (currentMillis * 1_000_000L) + (currentNanos % 1_000_000L);
        
        // Ensure monotonically increasing
        long last = lastTimestamp.get();
        if (combined <= last) {
            combined = last + 1;
        }
        
        // Atomic update with retry
        while (!lastTimestamp.compareAndSet(last, combined)) {
            last = lastTimestamp.get();
            if (combined <= last) {
                combined = last + 1;
            }
        }
        
        return combined;
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secretKey, HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to compute HMAC signature", e);
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GuardedOrderToken that = (GuardedOrderToken) o;
        return java.util.Arrays.equals(secretKey, that.secretKey);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(secretKey);
    }
}

