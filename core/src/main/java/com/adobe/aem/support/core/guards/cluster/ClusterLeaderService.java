package com.adobe.aem.support.core.guards.cluster;

/**
 * Service for determining if the current AEM instance is the cluster leader.
 * 
 * <p>In a clustered AEM environment, certain operations should only be performed
 * by one instance to avoid duplication. This service provides a consistent way
 * to check leadership status across components.</p>
 * 
 * <p>Usage:
 * <pre>{@code
 * @Reference
 * private ClusterLeaderService leaderService;
 * 
 * public void doWork() {
 *     if (!leaderService.isLeader()) {
 *         return; // Skip, not the leader
 *     }
 *     // Perform leader-only work
 * }
 * }</pre>
 * </p>
 */
public interface ClusterLeaderService {

    /**
     * Checks if the current instance is the cluster leader.
     * 
     * <p>In a single-instance deployment, this always returns true.
     * In a clustered deployment, only one instance will return true.</p>
     *
     * @return true if this instance is the leader, false otherwise
     */
    boolean isLeader();

    /**
     * Gets the Sling ID of the current instance.
     *
     * @return the unique Sling ID for this instance
     */
    String getSlingId();
}

