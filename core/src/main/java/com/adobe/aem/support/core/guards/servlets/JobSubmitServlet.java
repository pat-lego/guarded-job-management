package com.adobe.aem.support.core.guards.servlets;

import com.adobe.aem.support.core.guards.service.GuardedJob;
import com.adobe.aem.support.core.guards.service.JobProcessor;
import com.adobe.aem.support.core.guards.token.GuardedOrderTokenService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.sling.api.resource.ResourceResolver;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Servlet for submitting jobs via HTTP.
 * 
 * <p>Endpoint: POST /bin/guards/job.submit.json</p>
 * 
 * <p>Request body (JSON):
 * <pre>{@code
 * {
 *     "topic": "my-topic",
 *     "jobName": "echo",
 *     "parameters": {
 *         "message": "Hello world"
 *     }
 * }
 * }</pre>
 * </p>
 */
@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.paths=/bin/guards/job.submit",
        "sling.servlet.extensions=json",
        "sling.servlet.methods=POST"
    }
)
public class JobSubmitServlet extends SlingAllMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(JobSubmitServlet.class);
    private static final Gson GSON = new Gson();
    
    /**
     * Parameter key for the ResourceResolver passed to jobs.
     * Jobs that need the user's session should retrieve it from parameters using this key.
     */
    public static final String PARAM_RESOURCE_RESOLVER = "_resourceResolver";

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private JobProcessor jobProcessor;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private GuardedOrderTokenService tokenService;

    private final Map<String, GuardedJob<?>> jobs = new ConcurrentHashMap<>();

    @Reference(
        cardinality = ReferenceCardinality.MULTIPLE,
        policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY
    )
    protected void bindGuardedJob(GuardedJob<?> job) {
        String name = job.getName();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalStateException("GuardedJob must have a non-empty name: " + job.getClass().getName());
        }
        
        GuardedJob<?> existing = jobs.putIfAbsent(name, job);
        if (existing != null) {
            throw new IllegalStateException(
                "Duplicate job name '" + name + "' found. " +
                "Existing: " + existing.getClass().getName() + ", " +
                "New: " + job.getClass().getName()
            );
        }
        LOG.info("Registered job: {} -> {}", name, job.getClass().getName());
    }

    protected void unbindGuardedJob(GuardedJob<?> job) {
        String name = job.getName();
        jobs.remove(name, job);
        LOG.info("Unregistered job: {}", name);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        
        LOG.debug("JobSubmitServlet.doPost() called for path: {}", request.getPathInfo());
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();

        try {
            // Parse request body
            String body = request.getReader().lines().collect(Collectors.joining());
            LOG.debug("Request body: {}", body);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            // Extract required fields
            String topic = getRequiredString(json, "topic");
            String jobName = getRequiredString(json, "jobName");
            
            // Extract optional parameters
            Map<String, Object> parameters = new HashMap<>();
            if (json.has("parameters") && json.get("parameters").isJsonObject()) {
                JsonObject params = json.getAsJsonObject("parameters");
                for (String key : params.keySet()) {
                    parameters.put(key, GSON.fromJson(params.get(key), Object.class));
                }
            }
            
            // Pass the user's ResourceResolver to jobs that need it
            ResourceResolver resolver = request.getResourceResolver();
            parameters.put(PARAM_RESOURCE_RESOLVER, resolver);

            // Find the job
            GuardedJob<?> job = jobs.get(jobName);
            if (job == null) {
                sendError(response, writer, 400, "Unknown job name: " + jobName + 
                    ". Available jobs: " + String.join(", ", jobs.keySet()));
                return;
            }

            // Generate token and submit the job
            String token = tokenService.generateToken();
            LOG.debug("Generated token: {}", token);
            LOG.debug("Submitting job: topic={}, jobName={}, job={}", topic, jobName, job.getClass().getName());
            submitJob(topic, token, job, parameters);
            LOG.debug("Job submitted successfully");

            // Return success response
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("token", token);
            result.addProperty("topic", topic);
            result.addProperty("jobName", jobName);
            result.addProperty("message", "Job submitted successfully");
            
            writer.write(GSON.toJson(result));

        } catch (IllegalArgumentException e) {
            LOG.warn("IllegalArgumentException in job submission: {}", e.getMessage(), e);
            sendError(response, writer, 400, e.getMessage());
        } catch (Exception e) {
            LOG.error("Error submitting job: {}", e.getMessage(), e);
            sendError(response, writer, 500, "Internal error: " + e.getMessage());
        }
    }

    private String getRequiredString(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        String value = json.get(field).getAsString();
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("Field cannot be empty: " + field);
        }
        return value;
    }

    private void sendError(SlingHttpServletResponse response, PrintWriter writer, int status, String message) {
        response.setStatus(status);
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", message);
        writer.write(GSON.toJson(error));
    }

    private <T> void submitJob(String topic, String token, GuardedJob<T> job, Map<String, Object> parameters) {
        jobProcessor.submit(topic, token, job, parameters);
    }
}
