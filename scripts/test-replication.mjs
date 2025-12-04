#!/usr/bin/env node

/**
 * Script to test the ReplicationGuardedJob by:
 * 1. Creating test content under /content using Sling POST
 * 2. Submitting a replication job via the guarded job system
 * 3. Waiting for job completion
 * 4. Cleaning up (deleting) the test content
 * 
 * Usage: npm run test:replicate
 * 
 * Configuration: Create a .env file with:
 *   AEM_URL=http://localhost:4502
 *   AEM_USERNAME=admin
 *   AEM_PASSWORD=admin
 */

import 'dotenv/config';

// Read configuration from environment variables
const BASE_URL = process.env.AEM_URL || 'http://localhost:4502';
const AEM_USERNAME = process.env.AEM_USERNAME || 'admin';
const AEM_PASSWORD = process.env.AEM_PASSWORD || 'admin';
const POLL_INTERVAL_MS = parseInt(process.env.POLL_INTERVAL_MS || '1000', 10);

// Test configuration
const TEST_CONTENT_PATH = '/content/guarded-job-test';
const TEST_TOPIC = 'replication-test';

// Colors for console output
const colors = {
  reset: '\x1b[0m',
  bright: '\x1b[1m',
  dim: '\x1b[2m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  cyan: '\x1b[36m',
};

function log(message, color = colors.reset) {
  console.log(`${color}${message}${colors.reset}`);
}

function logStep(step, message) {
  console.log(`\n${colors.cyan}[Step ${step}]${colors.reset} ${colors.bright}${message}${colors.reset}`);
}

function logSuccess(message) {
  console.log(`  ${colors.green}✓${colors.reset} ${message}`);
}

function logError(message) {
  console.log(`  ${colors.red}✗${colors.reset} ${message}`);
}

function logInfo(message) {
  console.log(`  ${colors.dim}${message}${colors.reset}`);
}

function getAuthHeader() {
  return 'Basic ' + Buffer.from(`${AEM_USERNAME}:${AEM_PASSWORD}`).toString('base64');
}

/**
 * Create test content using Sling POST Servlet
 */
async function createTestContent() {
  const timestamp = Date.now();
  const testPages = [
    { name: `test-page-${timestamp}-1`, title: 'Test Page 1' },
    { name: `test-page-${timestamp}-2`, title: 'Test Page 2' },
    { name: `test-page-${timestamp}-3`, title: 'Test Page 3' },
  ];

  const createdPaths = [];

  // First, ensure the parent folder exists
  logInfo(`Creating parent folder: ${TEST_CONTENT_PATH}`);
  
  const parentFormData = new URLSearchParams();
  parentFormData.append('jcr:primaryType', 'sling:Folder');
  parentFormData.append('jcr:title', 'Guarded Job Test Content');

  try {
    const parentResponse = await fetch(`${BASE_URL}${TEST_CONTENT_PATH}`, {
      method: 'POST',
      headers: {
        'Authorization': getAuthHeader(),
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: parentFormData.toString(),
    });

    if (!parentResponse.ok && parentResponse.status !== 500) {
      // 500 might mean it already exists, which is fine
      const text = await parentResponse.text();
      logInfo(`Parent folder response: ${parentResponse.status} - ${text.substring(0, 100)}`);
    }
  } catch (error) {
    logInfo(`Parent folder might already exist: ${error.message}`);
  }

  // Create test pages
  for (const page of testPages) {
    const pagePath = `${TEST_CONTENT_PATH}/${page.name}`;
    
    const formData = new URLSearchParams();
    formData.append('jcr:primaryType', 'cq:Page');
    formData.append('jcr:content/jcr:primaryType', 'cq:PageContent');
    formData.append('jcr:content/jcr:title', page.title);
    formData.append('jcr:content/sling:resourceType', 'guarded-job-management/components/test');
    formData.append('jcr:content/testTimestamp', timestamp.toString());

    try {
      const response = await fetch(`${BASE_URL}${pagePath}`, {
        method: 'POST',
        headers: {
          'Authorization': getAuthHeader(),
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: formData.toString(),
      });

      if (response.ok || response.status === 201 || response.status === 200) {
        createdPaths.push(pagePath);
        logSuccess(`Created: ${pagePath}`);
      } else {
        const text = await response.text();
        logError(`Failed to create ${pagePath}: ${response.status} - ${text.substring(0, 100)}`);
      }
    } catch (error) {
      logError(`Error creating ${pagePath}: ${error.message}`);
    }
  }

  return createdPaths;
}

/**
 * Submit a replication job via the guarded job system
 */
async function submitReplicationJob(paths, action = 'ACTIVATE') {
  const payload = {
    topic: TEST_TOPIC,
    jobName: 'replicate',
    parameters: {
      paths: paths,
      action: action,
    },
  };

  logInfo(`Submitting ${action} job for ${paths.length} paths...`);
  logInfo(`Payload: ${JSON.stringify(payload, null, 2)}`);

  const response = await fetch(`${BASE_URL}/bin/guards/job.submit.json`, {
    method: 'POST',
    headers: {
      'Authorization': getAuthHeader(),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });

  const result = await response.json();

  if (response.ok && result.success) {
    logSuccess(`Job submitted: token=${result.token?.substring(0, 20)}...`);
    return result;
  } else {
    logError(`Failed to submit job: ${JSON.stringify(result)}`);
    throw new Error(`Job submission failed: ${result.message || response.status}`);
  }
}

/**
 * Wait for all jobs in the topic to complete
 */
async function waitForJobCompletion(topic, timeoutMs = 60000) {
  const startTime = Date.now();
  let lastPending = -1;

  while (Date.now() - startTime < timeoutMs) {
    const response = await fetch(`${BASE_URL}/bin/guards/job.status.json?topic=${topic}`, {
      headers: { 'Authorization': getAuthHeader() },
    });

    const status = await response.json();
    const pending = status.pendingCount || 0;

    if (pending !== lastPending) {
      logInfo(`Pending jobs: ${pending}`);
      lastPending = pending;
    }

    if (pending === 0) {
      const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
      logSuccess(`All jobs completed in ${elapsed}s`);
      return true;
    }

    await sleep(POLL_INTERVAL_MS);
  }

  logError(`Timeout waiting for jobs to complete (${timeoutMs / 1000}s)`);
  return false;
}

/**
 * Delete test content
 */
async function deleteTestContent(paths) {
  let deleted = 0;

  for (const path of paths) {
    try {
      const response = await fetch(`${BASE_URL}${path}`, {
        method: 'DELETE',
        headers: { 'Authorization': getAuthHeader() },
      });

      if (response.ok || response.status === 204) {
        deleted++;
        logSuccess(`Deleted: ${path}`);
      } else {
        logError(`Failed to delete ${path}: ${response.status}`);
      }
    } catch (error) {
      logError(`Error deleting ${path}: ${error.message}`);
    }
  }

  // Also try to delete the parent folder
  try {
    const response = await fetch(`${BASE_URL}${TEST_CONTENT_PATH}`, {
      method: 'DELETE',
      headers: { 'Authorization': getAuthHeader() },
    });
    if (response.ok || response.status === 204) {
      logSuccess(`Deleted parent: ${TEST_CONTENT_PATH}`);
    }
  } catch (error) {
    logInfo(`Could not delete parent folder: ${error.message}`);
  }

  return deleted;
}

/**
 * Verify content exists on author
 */
async function verifyContentExists(path) {
  try {
    const response = await fetch(`${BASE_URL}${path}.json`, {
      headers: { 'Authorization': getAuthHeader() },
    });
    return response.ok;
  } catch {
    return false;
  }
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Main test flow
 */
async function main() {
  console.log(`
${colors.bright}╔════════════════════════════════════════════════════════════╗
║           Guarded Job Replication Test Script              ║
╚════════════════════════════════════════════════════════════╝${colors.reset}
`);

  log(`Server: ${BASE_URL}`, colors.dim);
  log(`User: ${AEM_USERNAME}`, colors.dim);
  log(`Topic: ${TEST_TOPIC}`, colors.dim);

  let createdPaths = [];
  let success = false;

  try {
    // Step 1: Create test content
    logStep(1, 'Creating test content');
    createdPaths = await createTestContent();

    if (createdPaths.length === 0) {
      throw new Error('No test content was created');
    }

    // Verify content was created
    for (const path of createdPaths) {
      const exists = await verifyContentExists(path);
      if (!exists) {
        logError(`Content verification failed for: ${path}`);
      }
    }

    // Step 2: Submit replication (ACTIVATE) job
    logStep(2, 'Submitting ACTIVATE replication job');
    await submitReplicationJob(createdPaths, 'ACTIVATE');

    // Step 3: Wait for job completion
    logStep(3, 'Waiting for replication job to complete');
    const activated = await waitForJobCompletion(TEST_TOPIC, 60000);

    if (!activated) {
      logError('Replication job did not complete in time');
    } else {
      logSuccess('Content activated successfully!');
    }

    // Step 4: Submit DEACTIVATE job
    logStep(4, 'Submitting DEACTIVATE replication job');
    await submitReplicationJob(createdPaths, 'DEACTIVATE');

    // Step 5: Wait for deactivation
    logStep(5, 'Waiting for deactivation job to complete');
    const deactivated = await waitForJobCompletion(TEST_TOPIC, 60000);

    if (!deactivated) {
      logError('Deactivation job did not complete in time');
    } else {
      logSuccess('Content deactivated successfully!');
    }

    success = activated && deactivated;

  } catch (error) {
    logError(`Test failed: ${error.message}`);
    console.error(error);
  } finally {
    // Step 6: Cleanup - delete test content
    logStep(6, 'Cleaning up test content');
    await deleteTestContent(createdPaths);
  }

  // Summary
  console.log(`
${colors.bright}════════════════════════════════════════════════════════════${colors.reset}`);
  
  if (success) {
    log(`\n✅ Replication test completed successfully!\n`, colors.green);
  } else {
    log(`\n❌ Replication test completed with errors.\n`, colors.red);
    process.exit(1);
  }
}

// Run the test
main().catch(error => {
  console.error('Fatal error:', error);
  process.exit(1);
});

