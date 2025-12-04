package com.adobe.aem.support.core.guards.servlets;

import com.adobe.aem.support.core.guards.service.GuardedJob;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servlet for listing available job types.
 * 
 * <p>Endpoint: GET /bin/guards/job.list.json</p>
 * 
 * <p>Response (JSON):
 * <pre>{@code
 * {
 *     "jobs": [
 *         {"name": "echo", "class": "com.example.EchoJob"},
 *         {"name": "publish-page", "class": "com.example.PublishPageJob"}
 *     ],
 *     "count": 2
 * }
 * }</pre>
 * </p>
 */
@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.paths=/bin/guards/job.list",
        "sling.servlet.extensions=json",
        "sling.servlet.methods=GET"
    }
)
public class JobListServlet extends SlingSafeMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(JobListServlet.class);
    private static final Gson GSON = new Gson();

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
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();

        JsonObject result = new JsonObject();
        JsonArray jobArray = new JsonArray();
        
        for (Map.Entry<String, GuardedJob<?>> entry : jobs.entrySet()) {
            JsonObject jobInfo = new JsonObject();
            jobInfo.addProperty("name", entry.getKey());
            jobInfo.addProperty("class", entry.getValue().getClass().getName());
            jobArray.add(jobInfo);
        }
        
        result.add("jobs", jobArray);
        result.addProperty("count", jobs.size());
        
        writer.write(GSON.toJson(result));
    }
}

