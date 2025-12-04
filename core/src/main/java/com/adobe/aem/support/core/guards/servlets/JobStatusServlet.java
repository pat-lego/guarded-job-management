package com.adobe.aem.support.core.guards.servlets;

import com.adobe.aem.support.core.guards.service.JobProcessor;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Servlet for getting job queue status.
 * 
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /bin/guards/job.status.json - overall status</li>
 *   <li>GET /bin/guards/job.status.json?topic=my-topic - specific topic status</li>
 * </ul>
 */
@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.paths=/bin/guards/job.status",
        "sling.servlet.extensions=json",
        "sling.servlet.methods=GET"
    }
)
public class JobStatusServlet extends SlingSafeMethodsServlet {

    private static final Gson GSON = new Gson();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private JobProcessor jobProcessor;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();

        String topic = request.getParameter("topic");
        
        JsonObject result = new JsonObject();
        
        if (topic != null && !topic.trim().isEmpty()) {
            // Status for specific topic
            result.addProperty("topic", topic);
            result.addProperty("pendingCount", jobProcessor.getPendingCount(topic));
        } else {
            // Overall status
            result.addProperty("topic", "all");
            result.addProperty("totalPendingCount", jobProcessor.getTotalPendingCount());
        }
        
        result.addProperty("processorShutdown", jobProcessor.isShutdown());
        
        writer.write(GSON.toJson(result));
    }
}
