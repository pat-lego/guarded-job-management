package com.adobe.aem.support.core.guards.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuardedOrderTokenTest {

    private static final String SECRET_KEY = "test-secret-key-for-hmac-signing";
    private GuardedOrderToken tokenGenerator;

    @BeforeEach
    void setUp() {
        tokenGenerator = new GuardedOrderToken(SECRET_KEY);
    }

    @Test
    void generate_producesValidToken() {
        String token = tokenGenerator.generate();
        
        assertNotNull(token);
        assertTrue(token.contains("."));
        assertTrue(tokenGenerator.isValid(token));
    }

    @Test
    void generate_producesOrderedTokens() {
        String token1 = tokenGenerator.generate();
        String token2 = tokenGenerator.generate();
        String token3 = tokenGenerator.generate();

        // token1 < token2 < token3 in order
        assertTrue(tokenGenerator.compare(token1, token2) < 0);
        assertTrue(tokenGenerator.compare(token2, token3) < 0);
        assertTrue(tokenGenerator.compare(token1, token3) < 0);
    }

    @Test
    void compare_detectsTamperedTimestamp() {
        String token = tokenGenerator.generate();
        
        // Tamper with the timestamp
        String[] parts = token.split("\\.", 2);
        long originalTimestamp = Long.parseLong(parts[0]);
        String tamperedToken = (originalTimestamp + 1000) + "." + parts[1];
        
        assertFalse(tokenGenerator.isValid(tamperedToken));
        assertThrows(IllegalArgumentException.class, 
            () -> tokenGenerator.compare(token, tamperedToken));
    }

    @Test
    void compare_detectsTamperedSignature() {
        String token = tokenGenerator.generate();
        
        // Tamper with the signature
        String[] parts = token.split("\\.", 2);
        String tamperedToken = parts[0] + ".tampered-signature";
        
        assertFalse(tokenGenerator.isValid(tamperedToken));
    }

    @Test
    void isValid_returnsFalseForNullOrEmpty() {
        assertFalse(tokenGenerator.isValid(null));
        assertFalse(tokenGenerator.isValid(""));
    }

    @Test
    void isValid_returnsFalseForMalformedToken() {
        assertFalse(tokenGenerator.isValid("no-delimiter"));
        assertFalse(tokenGenerator.isValid("not-a-number.signature"));
        assertFalse(tokenGenerator.isValid(".only-signature"));
    }

    @Test
    void constructor_throwsForNullOrEmptyKey() {
        assertThrows(IllegalArgumentException.class, 
            () -> new GuardedOrderToken((String) null));
        assertThrows(IllegalArgumentException.class, 
            () -> new GuardedOrderToken(""));
        assertThrows(IllegalArgumentException.class, 
            () -> new GuardedOrderToken((byte[]) null));
        assertThrows(IllegalArgumentException.class, 
            () -> new GuardedOrderToken(new byte[0]));
    }

    @Test
    void differentSecretKeys_produceIncompatibleTokens() {
        GuardedOrderToken generator1 = new GuardedOrderToken("secret-key-1");
        GuardedOrderToken generator2 = new GuardedOrderToken("secret-key-2");
        
        String token = generator1.generate();
        
        assertTrue(generator1.isValid(token));
        assertFalse(generator2.isValid(token));
    }

    @Test
    void comparator_sortsTokensCorrectly() {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tokens.add(tokenGenerator.generate());
        }
        
        // Shuffle and then sort
        List<String> shuffled = new ArrayList<>(tokens);
        Collections.shuffle(shuffled);
        shuffled.sort(tokenGenerator.comparator());
        
        assertEquals(tokens, shuffled);
    }

    @Test
    void extractTimestamp_returnsCorrectValue() {
        String token = tokenGenerator.generate();
        
        long timestamp = tokenGenerator.extractTimestamp(token);
        
        assertTrue(timestamp > 0);
    }

    @Test
    void extractTimestamp_throwsForInvalidToken() {
        assertThrows(IllegalArgumentException.class, 
            () -> tokenGenerator.extractTimestamp("invalid.token"));
    }

    @Test
    void rapidGeneration_maintainsOrdering() {
        // Generate many tokens rapidly to test monotonic ordering
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(tokenGenerator.generate());
        }
        
        // Verify all are valid and properly ordered
        for (int i = 0; i < tokens.size() - 1; i++) {
            assertTrue(tokenGenerator.isValid(tokens.get(i)));
            assertTrue(tokenGenerator.compare(tokens.get(i), tokens.get(i + 1)) < 0,
                "Token at index " + i + " should be less than token at index " + (i + 1));
        }
    }

    @Test
    void sameTimestamp_differentTokens() {
        // Even if called at the "same time", tokens should be unique and orderable
        String token1 = tokenGenerator.generate();
        String token2 = tokenGenerator.generate();
        
        assertNotEquals(token1, token2);
        assertNotEquals(0, tokenGenerator.compare(token1, token2));
    }
}

