package com.adobe.aem.support.core.guards.persistence;

import java.util.List;
import java.util.Map;

/**
 * Service for persisting jobs to durable storage (e.g., JCR repository).
 * 
 * <p>This allows jobs to survive JVM restarts. When the system starts up,
 * persisted jobs are loaded and resubmitted for processing.</p>
 * 
 * <p>Usage:
 * <pre>{@code
 * // Persist a job before processing
 * String persistenceId = persistenceService.persist(topic, token, jobName, parameters);
 * 
 * // After job completes successfully
 * persistenceService.remove(persistenceId);
 * 
 * // On startup, load all persisted jobs
 * List<PersistedJob> jobs = persistenceService.loadAll();
 * }</pre>
 * </p>
 */
public interface JobPersistenceService {

    /**
     * Persists a job to durable storage.
     *
     * @param topic       the job topic
     * @param token       the guarded order token
     * @param jobName     the name of the job implementation
     * @param submittedBy the user ID who submitted the job
     * @param parameters  the job parameters
     * @return a unique persistence ID for later removal
     * @throws JobPersistenceException if persistence fails
     */
    String persist(String topic, String token, String jobName, String submittedBy, Map<String, Object> parameters) 
        throws JobPersistenceException;

    /**
     * Removes a persisted job after successful completion.
     *
     * @param persistenceId the ID returned from {@link #persist}
     * @throws JobPersistenceException if removal fails
     */
    void remove(String persistenceId) throws JobPersistenceException;

    /**
     * Loads all persisted jobs from storage.
     * 
     * <p>Called during system startup to recover jobs that were
     * not completed before the previous shutdown.</p>
     *
     * @return list of all persisted jobs
     * @throws JobPersistenceException if loading fails
     */
    List<PersistedJob> loadAll() throws JobPersistenceException;

    /**
     * Loads persisted jobs for a specific topic, ordered by token timestamp.
     *
     * @param topic the topic to filter by
     * @param limit maximum number of jobs to return
     * @return list of persisted jobs for the topic, ordered by token timestamp ascending
     * @throws JobPersistenceException if loading fails
     */
    List<PersistedJob> loadByTopic(String topic, int limit) throws JobPersistenceException;

    /**
     * Checks if persistence is enabled.
     *
     * @return true if jobs will be persisted, false otherwise
     */
    boolean isEnabled();

    /**
     * Represents a job loaded from persistent storage.
     */
    class PersistedJob {
        private final String persistenceId;
        private final String topic;
        private final String token;
        private final String jobName;
        private final String submittedBy;
        private final Map<String, Object> parameters;
        private final long persistedAt;

        public PersistedJob(String persistenceId, String topic, String token, 
                          String jobName, String submittedBy, Map<String, Object> parameters, long persistedAt) {
            this.persistenceId = persistenceId;
            this.topic = topic;
            this.token = token;
            this.jobName = jobName;
            this.submittedBy = submittedBy;
            this.parameters = parameters;
            this.persistedAt = persistedAt;
        }

        public String getPersistenceId() {
            return persistenceId;
        }

        public String getTopic() {
            return topic;
        }

        public String getToken() {
            return token;
        }

        public String getJobName() {
            return jobName;
        }

        public String getSubmittedBy() {
            return submittedBy;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public long getPersistedAt() {
            return persistedAt;
        }
    }

    /**
     * Exception thrown when job persistence operations fail.
     */
    class JobPersistenceException extends Exception {
        public JobPersistenceException(String message) {
            super(message);
        }

        public JobPersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

