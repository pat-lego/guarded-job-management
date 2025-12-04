package com.adobe.aem.support.core.guards.token.impl;

import com.adobe.aem.support.core.guards.token.GuardedOrderToken;
import com.adobe.aem.support.core.guards.token.GuardedOrderTokenService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi implementation of {@link GuardedOrderTokenService}.
 * 
 * <p>The secret key is configured via OSGi configuration and shared
 * across all instances to ensure tokens can be validated on any node.</p>
 */
@Component(service = GuardedOrderTokenService.class, immediate = true)
@Designate(ocd = GuardedOrderTokenServiceImpl.Config.class)
public class GuardedOrderTokenServiceImpl implements GuardedOrderTokenService {

    @ObjectClassDefinition(
        name = "Guarded Order Token Service",
        description = "Configuration for the tamper-proof order token service"
    )
    @interface Config {
        @AttributeDefinition(
            name = "Secret Key",
            description = "Shared secret key for signing tokens. Must be the same across all cluster nodes.",
            type = AttributeType.PASSWORD
        )
        String secretKey() default "";
    }

    private volatile GuardedOrderToken tokenGenerator;

    @Activate
    public GuardedOrderTokenServiceImpl(Config config) {
        String secretKey = config.secretKey();
        if (secretKey == null || secretKey.trim().isEmpty()) {
            throw new IllegalStateException("Secret key must be configured for GuardedOrderTokenService");
        }
        this.tokenGenerator = new GuardedOrderToken(secretKey);
    }

    @Override
    public String generateToken() {
        return tokenGenerator.generate();
    }

    @Override
    public boolean isValid(String token) {
        return tokenGenerator.isValid(token);
    }

    @Override
    public long extractTimestamp(String token) {
        return tokenGenerator.extractTimestamp(token);
    }

    @Override
    public int compare(String token1, String token2) {
        return tokenGenerator.compare(token1, token2);
    }
}

