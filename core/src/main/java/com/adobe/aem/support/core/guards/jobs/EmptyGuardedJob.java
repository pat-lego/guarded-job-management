package com.adobe.aem.support.core.guards.jobs;

import com.adobe.aem.support.core.guards.service.GuardedJob;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * A simple no-op job that just logs a message.
 * Useful for testing the job processing infrastructure.
 */
@Component(service = GuardedJob.class)
public class EmptyGuardedJob implements GuardedJob<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(EmptyGuardedJob.class);

    @Override
    public String getName() {
        return "empty";
    }

    @Override
    public Void execute(Map<String, Object> parameters) {
        LOG.info("EmptyGuardedJob executed");
        return null;
    }
}

