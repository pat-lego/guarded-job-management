package com.adobe.aem.support.core.guards.servlets;

import com.adobe.aem.support.core.guards.persistence.JobPersistenceService;
import com.adobe.aem.support.core.guards.persistence.JobPersistenceService.JobPersistenceException;
import com.adobe.aem.support.core.guards.persistence.JobPersistenceService.PersistedJob;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Servlet for retrieving detailed job information by topic.
 * 
 * <p>Endpoint: GET /bin/guards/job.details.json?topic={topic}&amp;limit={limit}</p>
 * 
 * <p>Query Parameters:</p>
 * <ul>
 *   <li><b>topic</b> (required) - The topic to query jobs for</li>
 *   <li><b>limit</b> (optional, default: 100, max: 100) - Maximum number of jobs to return</li>
 * </ul>
 * 
 * <p>Response (JSON):
 * <pre>{@code
 * {
 *     "topic": "my-topic",
 *     "count": 5,
 *     "jobs": [
 *         {
 *             "token": "1733325600001234567.abc123...",
 *             "jobName": "echo",
 *             "submittedBy": "admin",
 *             "persistedAt": "2024-12-04T15:00:00Z",
 *             "persistenceId": "/var/guarded-jobs/sling-id/2024/12/04/uuid"
 *         },
 *         ...
 *     ]
 * }
 * }</pre>
 * </p>
 */
@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.paths=/bin/guards/job.details",
        "sling.servlet.extensions=json",
        "sling.servlet.methods=GET"
    }
)
public class JobDetailsServlet extends SlingSafeMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(JobDetailsServlet.class);
    private static final Gson GSON = new Gson();
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 100;
    private static final DateTimeFormatter ISO_FORMATTER = 
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"));

    @Reference
    private JobPersistenceService persistenceService;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();

        // Get required topic parameter
        String topic = request.getParameter("topic");
        if (topic == null || topic.trim().isEmpty()) {
            sendError(response, writer, 400, "Missing required parameter: topic");
            return;
        }

        // Get optional limit parameter
        int limit = DEFAULT_LIMIT;
        String limitParam = request.getParameter("limit");
        if (limitParam != null && !limitParam.trim().isEmpty()) {
            try {
                limit = Integer.parseInt(limitParam);
                if (limit <= 0) {
                    limit = DEFAULT_LIMIT;
                } else if (limit > MAX_LIMIT) {
                    limit = MAX_LIMIT;
                }
            } catch (NumberFormatException e) {
                sendError(response, writer, 400, "Invalid limit parameter: " + limitParam);
                return;
            }
        }

        try {
            List<PersistedJob> jobs = persistenceService.loadByTopic(topic, limit);
            
            JsonObject result = new JsonObject();
            result.addProperty("topic", topic);
            result.addProperty("count", jobs.size());
            
            JsonArray jobArray = new JsonArray();
            for (PersistedJob job : jobs) {
                JsonObject jobInfo = new JsonObject();
                jobInfo.addProperty("token", job.getToken());
                jobInfo.addProperty("jobName", job.getJobName());
                jobInfo.addProperty("submittedBy", job.getSubmittedBy());
                jobInfo.addProperty("persistedAt", formatTimestamp(job.getPersistedAt()));
                jobInfo.addProperty("persistenceId", job.getPersistenceId());
                jobArray.add(jobInfo);
            }
            result.add("jobs", jobArray);
            
            writer.write(GSON.toJson(result));
            
            LOG.debug("Returned {} jobs for topic '{}'", jobs.size(), topic);

        } catch (JobPersistenceException e) {
            LOG.error("Failed to load jobs for topic '{}': {}", topic, e.getMessage(), e);
            sendError(response, writer, 500, "Failed to load jobs: " + e.getMessage());
        }
    }

    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0) {
            return "unknown";
        }
        return ISO_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    private void sendError(SlingHttpServletResponse response, PrintWriter writer, int status, String message) {
        response.setStatus(status);
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", message);
        writer.write(GSON.toJson(error));
    }
}

