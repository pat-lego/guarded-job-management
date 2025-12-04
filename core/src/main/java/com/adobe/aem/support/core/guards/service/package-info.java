/**
 * Service package containing job processing utilities with guaranteed ordering.
 * 
 * <p>The main components are:
 * <ul>
 *   <li>{@link com.adobe.aem.support.core.guards.service.GuardedJob} - Interface for jobs to be processed</li>
 *   <li>{@link com.adobe.aem.support.core.guards.service.JobProcessor} - Service interface for job submission</li>
 *   <li>{@link com.adobe.aem.support.core.guards.service.OrderedJobProcessor} - Implementation with per-topic ordering</li>
 * </ul>
 * </p>
 */
@Version("1.0.0")
package com.adobe.aem.support.core.guards.service;

import org.osgi.annotation.versioning.Version;

