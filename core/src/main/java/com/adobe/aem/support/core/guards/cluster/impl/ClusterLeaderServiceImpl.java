package com.adobe.aem.support.core.guards.cluster.impl;

import com.adobe.aem.support.core.guards.cluster.ClusterLeaderService;
import org.apache.sling.discovery.TopologyEvent;
import org.apache.sling.discovery.TopologyEventListener;
import org.apache.sling.discovery.TopologyView;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link ClusterLeaderService} using Sling Discovery API.
 * 
 * <p>Listens to topology events to track leadership changes in the cluster.
 * When the topology changes (e.g., instance joins/leaves), leadership may
 * be reassigned.</p>
 */
@Component(service = {ClusterLeaderService.class, TopologyEventListener.class}, immediate = true)
public class ClusterLeaderServiceImpl implements ClusterLeaderService, TopologyEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterLeaderServiceImpl.class);

    @Reference
    private SlingSettingsService slingSettings;

    private volatile boolean isLeader = false;
    private String slingId;

    @Activate
    protected void activate() {
        this.slingId = slingSettings.getSlingId();
        LOG.info("ClusterLeaderService activated: slingId={}", slingId);
    }

    @Override
    public void handleTopologyEvent(TopologyEvent event) {
        TopologyView newView = event.getNewView();
        
        if (newView == null) {
            // Topology is undefined (e.g., during startup or network partition)
            boolean wasLeader = isLeader;
            isLeader = false;
            if (wasLeader) {
                LOG.warn("Topology view is null, relinquishing leadership");
            }
            return;
        }

        boolean wasLeader = isLeader;
        isLeader = newView.getLocalInstance().isLeader();
        
        if (wasLeader != isLeader) {
            LOG.info("Leadership changed: isLeader={}, slingId={}, event={}", 
                isLeader, slingId, event.getType());
        }
    }

    @Override
    public boolean isLeader() {
        return isLeader;
    }

    @Override
    public String getSlingId() {
        return slingId;
    }
}

