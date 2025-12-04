package com.adobe.aem.support.core.guards.jobs;

import com.adobe.aem.support.core.guards.service.GuardedJob;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Example job that echoes the input message.
 * 
 * <p>Parameters:
 * <ul>
 *   <li>message (required) - The message to echo</li>
 *   <li>delay (optional) - Delay in milliseconds before returning (for testing)</li>
 * </ul>
 * </p>
 */
@Component(service = GuardedJob.class)
public class EchoJob implements GuardedJob<String> {

    private static final Logger LOG = LoggerFactory.getLogger(EchoJob.class);

    @Override
    public String getName() {
        return "echo";
    }

    @Override
    public String execute(Map<String, Object> parameters) throws Exception {
        Object messageObj = parameters.get("message");
        if (messageObj == null) {
            throw new IllegalArgumentException("Missing required parameter: message");
        }
        String message = messageObj.toString();
        
        // Optional delay for testing/demonstration
        Object delayObj = parameters.get("delay");
        if (delayObj != null) {
            long delay = delayObj instanceof Number 
                ? ((Number) delayObj).longValue() 
                : Long.parseLong(delayObj.toString());
            if (delay > 0) {
                LOG.debug("Echo job sleeping for {}ms", delay);
                Thread.sleep(delay);
            }
        }
        
        LOG.info("Echo job executed with message: {}", message);
        return "Echo: " + message;
    }
}
