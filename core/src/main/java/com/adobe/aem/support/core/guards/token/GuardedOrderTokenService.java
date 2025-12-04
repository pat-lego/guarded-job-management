package com.adobe.aem.support.core.guards.token;

/**
 * OSGi service for generating and validating tamper-proof order tokens.
 * 
 * <p>Tokens are used to establish ordering across distributed clients.
 * A token generated at time A will always sort before a token generated
 * at time B (when A &lt; B), regardless of when the tokens arrive at the server.</p>
 * 
 * <p>Usage:
 * <pre>{@code
 * @Reference
 * private GuardedOrderTokenService tokenService;
 * 
 * // Generate a token (establishes position in order)
 * String token = tokenService.generateToken();
 * 
 * // Later, validate before processing
 * if (tokenService.isValid(token)) {
 *     // process in token order
 * }
 * }</pre>
 * </p>
 */
public interface GuardedOrderTokenService {

    /**
     * Generates a new order token based on the current time.
     * 
     * <p>Each call produces a token with a strictly increasing timestamp.
     * The token is signed to prevent tampering.</p>
     *
     * @return a signed, orderable token string
     */
    String generateToken();

    /**
     * Validates whether a token is authentic and has not been tampered with.
     *
     * @param token the token to validate
     * @return true if the token is valid and unmodified, false otherwise
     */
    boolean isValid(String token);

    /**
     * Extracts the timestamp from a valid token.
     * 
     * @param token the token to extract timestamp from
     * @return the timestamp value
     * @throws IllegalArgumentException if the token is invalid
     */
    long extractTimestamp(String token);

    /**
     * Compares two tokens based on their creation time.
     * 
     * @param token1 the first token
     * @param token2 the second token
     * @return negative if token1 was created before token2,
     *         zero if created at the same time,
     *         positive if token1 was created after token2
     * @throws IllegalArgumentException if either token is invalid
     */
    int compare(String token1, String token2);
}

