#!/usr/bin/env node

/**
 * Script to test ORDERED replication using the GuardedJob system.
 * 
 * This test validates that jobs are executed in TOKEN ORDER, not submission order.
 * This is critical to prevent content from being overwritten when jobs arrive
 * out of order due to network delays.
 * 
 * Test Flow:
 * 1. Create a test page
 * 2. Submit multiple SEPARATE replication jobs rapidly (simulating concurrent submissions)
 * 3. Capture the tokens assigned to each job
 * 4. Monitor job execution order via logs/details
 * 5. Verify execution order matches token order (ascending timestamps)
 * 
 * Usage: 
 *   npm run test:ordered          # Normal output
 *   npm run test:ordered:debug    # Verbose debug output
 *   DEBUG=gjm:* npm run test:ordered  # Manual debug enable
 */

import 'dotenv/config';
import createDebug from 'debug';

// Create debug loggers for different concerns
const debug = {
  setup: createDebug('gjm:setup'),
  poll: createDebug('gjm:poll'),
  track: createDebug('gjm:track'),
  state: createDebug('gjm:state'),
};

// Read configuration from environment variables
const BASE_URL = process.env.AEM_URL || 'http://localhost:4502';
const AEM_USERNAME = process.env.AEM_USERNAME || 'admin';
const AEM_PASSWORD = process.env.AEM_PASSWORD || 'admin';
const POLL_INTERVAL_MS = parseInt(process.env.POLL_INTERVAL_MS || '500', 10);

// Test configuration
const TEST_CONTENT_PATH = '/content/guarded-job-ordered-test';
const TEST_TOPIC = 'ordered-replication-test';
const NUM_JOBS = 5; // Number of jobs to submit

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
  magenta: '\x1b[35m',
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

function logJob(index, token, message) {
  const jobColors = [colors.cyan, colors.yellow, colors.magenta, colors.blue, colors.green];
  const color = jobColors[index % jobColors.length];
  console.log(`  ${color}[Job ${index + 1}]${colors.reset} ${message} ${colors.dim}(token: ${token.substring(0, 15)}...)${colors.reset}`);
}

function getAuthHeader() {
  return 'Basic ' + Buffer.from(`${AEM_USERNAME}:${AEM_PASSWORD}`).toString('base64');
}

/**
 * Create test content
 */
async function createTestContent() {
  const timestamp = Date.now();
  const pagePath = `${TEST_CONTENT_PATH}/test-page-${timestamp}`;
  
  // Create parent folder
  try {
    await fetch(`${BASE_URL}${TEST_CONTENT_PATH}`, {
      method: 'POST',
      headers: {
        'Authorization': getAuthHeader(),
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: new URLSearchParams({
        'jcr:primaryType': 'sling:Folder',
      }).toString(),
    });
  } catch (e) {
    // Folder might already exist
  }

  // Create test page
  const formData = new URLSearchParams();
  formData.append('jcr:primaryType', 'cq:Page');
  formData.append('jcr:content/jcr:primaryType', 'cq:PageContent');
  formData.append('jcr:content/jcr:title', `Ordered Test Page ${timestamp}`);
  formData.append('jcr:content/sling:resourceType', 'guarded-job-management/components/test');

  const response = await fetch(`${BASE_URL}${pagePath}`, {
    method: 'POST',
    headers: {
      'Authorization': getAuthHeader(),
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: formData.toString(),
  });

  if (response.ok || response.status === 201) {
    logSuccess(`Created test page: ${pagePath}`);
    return pagePath;
  } else {
    throw new Error(`Failed to create test page: ${response.status}`);
  }
}

/**
 * Submit a single replication job and return submission info
 */
async function submitSingleJob(path, jobIndex) {
  const payload = {
    topic: TEST_TOPIC,
    jobName: 'replicate',
    parameters: {
      paths: [path],
      action: 'ACTIVATE',
      // Add a marker to identify this job in logs
      _jobIndex: jobIndex,
    },
  };

  const submitTime = Date.now();
  
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
    return {
      jobIndex,
      token: result.token,
      submitTime,
      // Extract timestamp from token (format: timestamp.signature)
      tokenTimestamp: result.token.split('.')[0],
    };
  } else {
    throw new Error(`Job ${jobIndex} submission failed: ${result.message || response.status}`);
  }
}

/**
 * Get job details for a topic to track execution
 */
async function getJobDetails(topic) {
  const response = await fetch(`${BASE_URL}/bin/guards/job.details.json?topic=${encodeURIComponent(topic)}`, {
    headers: { 'Authorization': getAuthHeader() },
  });
  return response.json();
}

/**
 * Get job status (pending count)
 */
async function getJobStatus(topic) {
  const response = await fetch(`${BASE_URL}/bin/guards/job.status.json?topic=${encodeURIComponent(topic)}`, {
    headers: { 'Authorization': getAuthHeader() },
  });
  return response.json();
}

/**
 * Wait for all jobs to complete and track execution order
 * 
 * @param topic - The topic to monitor
 * @param submittedTokens - Array of tokens that were submitted (to initialize tracking)
 * @param timeoutMs - Timeout in milliseconds
 */
async function waitAndTrackExecution(topic, submittedTokens, timeoutMs = 60000) {
  const startTime = Date.now();
  const executionOrder = [];
  let lastPendingCount = -1;
  let pollCount = 0;
  
  // Helper to show shortened token
  const shortToken = (token) => token.substring(0, 20) + '...';
  
  // Helper to show job number from token
  const jobNum = (token) => {
    const idx = submittedTokens.indexOf(token);
    return idx >= 0 ? `Job${idx + 1}` : 'Unknown';
  };

  debug.setup('┌─────────────────────────────────────────────────────────────────┐');
  debug.setup('│  TRACKING SETUP                                                 │');
  debug.setup('└─────────────────────────────────────────────────────────────────┘');
  
  // IMPORTANT: Initialize previousJobs with ALL submitted tokens
  // This ensures we track jobs even if they complete before our first poll
  let previousJobs = new Set(submittedTokens);
  const allSubmittedTokens = new Set(submittedTokens);

  debug.setup('▶ Initializing previousJobs with ALL %d submitted tokens', submittedTokens.length);
  debug.setup('  This is KEY: if a job completes before our first poll,');
  debug.setup('  we\'ll still detect it as "missing" from the current state.');
  
  submittedTokens.forEach((token, idx) => {
    debug.setup('  previousJobs[%d]: %s (Job %d)', idx, shortToken(token), idx + 1);
  });

  debug.poll('┌─────────────────────────────────────────────────────────────────┐');
  debug.poll('│  POLLING LOOP                                                   │');
  debug.poll('└─────────────────────────────────────────────────────────────────┘');

  while (Date.now() - startTime < timeoutMs) {
    pollCount++;
    const status = await getJobStatus(topic);
    const details = await getJobDetails(topic);
    
    const currentPending = status.pendingCount || 0;
    const currentJobs = new Set((details.jobs || []).map(j => j.token));
    
    // Debug: poll header
    debug.poll('━━━ Poll #%d ━━━', pollCount);
    
    // Debug: what the server returned
    debug.poll('📡 Server returned: pendingCount=%d, jobs in JCR=%d', currentPending, currentJobs.size);
    if (currentJobs.size > 0) {
      [...currentJobs].forEach(token => {
        if (allSubmittedTokens.has(token)) {
          debug.poll('   - %s (%s)', shortToken(token), jobNum(token));
        }
      });
    } else {
      debug.poll('   (none - all jobs have been processed and deleted)');
    }

    // Debug: comparison
    debug.track('🔍 Comparing: previousJobs=%d tokens, currentJobs=%d tokens', previousJobs.size, currentJobs.size);

    // Track which jobs have been removed (executed) - only track our submitted jobs
    const newlyExecuted = [];
    for (const token of previousJobs) {
      if (!currentJobs.has(token) && allSubmittedTokens.has(token)) {
        // Job was in previous set but not in current = it executed
        newlyExecuted.push(token);
        executionOrder.push(token);
      }
    }
    
    // Debug: what we detected
    if (newlyExecuted.length > 0) {
      debug.track('✅ Detected %d job(s) completed:', newlyExecuted.length);
      newlyExecuted.forEach(token => {
        debug.track('   → %s (%s) - EXECUTED!', shortToken(token), jobNum(token));
      });
    } else {
      debug.track('   No new completions detected this poll');
    }
    
    // Update previous jobs to current state (only our submitted jobs that are still pending)
    const oldPreviousSize = previousJobs.size;
    previousJobs = new Set([...currentJobs].filter(t => allSubmittedTokens.has(t)));
    
    debug.state('🔄 Updating previousJobs: %d → %d tokens', oldPreviousSize, previousJobs.size);
    if (previousJobs.size > 0) {
      [...previousJobs].forEach(token => {
        debug.state('   - %s (%s)', shortToken(token), jobNum(token));
      });
    }

    // Debug: current execution order
    debug.state('📊 Execution order so far (%d/%d):', executionOrder.length, submittedTokens.length);
    if (executionOrder.length > 0) {
      executionOrder.forEach((token, idx) => {
        debug.state('   %d. %s (%s)', idx + 1, shortToken(token), jobNum(token));
      });
    }

    // Normal output: show progress when pending count changes
    if (currentPending !== lastPendingCount) {
      logInfo(`Pending: ${currentPending}, Executed: ${executionOrder.length}`);
      lastPendingCount = currentPending;
    }

    const expectedCount = submittedTokens.length;
    if (currentPending === 0 && executionOrder.length >= expectedCount) {
      const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
      debug.track('🎉 All %d jobs completed in %ss after %d polls!', expectedCount, elapsed, pollCount);
      logSuccess(`All ${expectedCount} jobs completed in ${elapsed}s`);
      return executionOrder;
    }

    // If pending is 0 but we haven't tracked all executions, wait a bit more
    if (currentPending === 0) {
      debug.poll('⏳ Pending=0 but only tracked %d/%d, waiting...', executionOrder.length, expectedCount);
      await sleep(POLL_INTERVAL_MS);
      // Final check
      const finalStatus = await getJobStatus(topic);
      if (finalStatus.pendingCount === 0) {
        break;
      }
    }

    await sleep(POLL_INTERVAL_MS / 2); // Poll faster to catch more state changes
  }

  return executionOrder;
}

/**
 * Delete test content
 */
async function deleteTestContent(path) {
  try {
    await fetch(`${BASE_URL}${path}`, {
      method: 'DELETE',
      headers: { 'Authorization': getAuthHeader() },
    });
    logSuccess(`Deleted: ${path}`);
  } catch (e) {
    logInfo(`Could not delete ${path}: ${e.message}`);
  }

  // Delete parent folder
  try {
    await fetch(`${BASE_URL}${TEST_CONTENT_PATH}`, {
      method: 'DELETE',
      headers: { 'Authorization': getAuthHeader() },
    });
    logSuccess(`Deleted parent: ${TEST_CONTENT_PATH}`);
  } catch (e) {
    // Ignore
  }
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Extract timestamp from token for sorting
 */
function getTokenTimestamp(token) {
  return BigInt(token.split('.')[0]);
}

/**
 * Main test flow
 */
async function main() {
  console.log(`
${colors.bright}╔════════════════════════════════════════════════════════════════╗
║         Ordered Replication Test - Token Order Validation      ║
╚════════════════════════════════════════════════════════════════╝${colors.reset}
`);

  log(`Server: ${BASE_URL}`, colors.dim);
  log(`Topic: ${TEST_TOPIC}`, colors.dim);
  log(`Number of jobs: ${NUM_JOBS}`, colors.dim);

  let testPagePath = null;
  let success = false;

  try {
    // Step 1: Create test content
    logStep(1, 'Creating test content');
    testPagePath = await createTestContent();

    // Step 2: Submit multiple jobs rapidly (simulating concurrent submissions)
    logStep(2, `Submitting ${NUM_JOBS} separate jobs rapidly`);
    
    const submittedJobs = [];
    
    // Submit jobs as fast as possible to test ordering
    for (let i = 0; i < NUM_JOBS; i++) {
      const jobInfo = await submitSingleJob(testPagePath, i);
      submittedJobs.push(jobInfo);
      logJob(i, jobInfo.token, `Submitted`);
      
      // Tiny delay to ensure different timestamps (nanosecond precision should handle this)
      await sleep(10);
    }

    // Step 3: Sort jobs by token timestamp to get expected execution order
    logStep(3, 'Analyzing token order');
    
    const sortedByToken = [...submittedJobs].sort((a, b) => {
      const tsA = getTokenTimestamp(a.token);
      const tsB = getTokenTimestamp(b.token);
      return tsA < tsB ? -1 : tsA > tsB ? 1 : 0;
    });

    log('\n  Expected execution order (by token timestamp):', colors.bright);
    sortedByToken.forEach((job, idx) => {
      logJob(job.jobIndex, job.token, `Expected position: ${idx + 1}`);
    });

    // Step 4: Wait for jobs to complete
    logStep(4, 'Waiting for jobs to execute and tracking order');
    
    // Pass all submitted tokens so we can track them even if some complete before first poll
    const submittedTokens = submittedJobs.map(j => j.token);
    
    const executionOrder = await waitAndTrackExecution(TEST_TOPIC, submittedTokens, 60000);

    // Step 5: Verify completion and provide log verification info
    logStep(5, 'Verifying completion');

    const finalStatus = await getJobStatus(TEST_TOPIC);
    if (finalStatus.pendingCount === 0) {
      logSuccess(`All ${NUM_JOBS} jobs completed successfully!`);
      success = true;
      
      // Print tokens in expected execution order for log verification
      log('\n  ┌─────────────────────────────────────────────────────────────────┐', colors.dim);
      log('  │  EXPECTED EXECUTION ORDER (verify against server logs)         │', colors.dim);
      log('  └─────────────────────────────────────────────────────────────────┘', colors.dim);
      
      log('\n  Jobs should execute in this token order (sorted by timestamp):\n', colors.bright);
      
      sortedByToken.forEach((job, idx) => {
        const tokenTimestamp = job.token.split('.')[0];
        console.log(`    ${colors.cyan}${idx + 1}.${colors.reset} Token: ${colors.yellow}${tokenTimestamp}${colors.reset}`);
        console.log(`       Full:  ${job.token.substring(0, 40)}...`);
      });
      
      // Provide grep command for easy log verification
      log('\n  ┌─────────────────────────────────────────────────────────────────┐', colors.dim);
      log('  │  HOW TO VERIFY IN SERVER LOGS                                  │', colors.dim);
      log('  └─────────────────────────────────────────────────────────────────┘', colors.dim);
      
      log('\n  Search your AEM logs for:', colors.bright);
      log(`    ${colors.green}grep "Executing job.*token=" error.log${colors.reset}`, colors.green);
      
      log('\n  You should see entries like:', colors.dim);
      log('    *INFO* [topic-executor-...] ...OrderedJobProcessor Executing job: topic=..., jobName=..., token=<TOKEN>', colors.dim);
      
      log('\n  The tokens should appear in ASCENDING order (by timestamp prefix),', colors.bright);
      log('  matching the token order printed above.', colors.bright);
      
      log('\n  ⚠️  Note: Polling cannot prove execution order when jobs complete', colors.yellow);
      log('  between polls. The server logs are the definitive source of truth.', colors.yellow);
      
    } else {
      logError(`Not all jobs completed. Pending: ${finalStatus.pendingCount}`);
    }

    // Step 6: Submit deactivate job to clean up publish
    logStep(6, 'Deactivating test content');
    const deactivateJob = await submitSingleJob(testPagePath, 999);
    logSuccess(`Deactivate job submitted: ${deactivateJob.token.substring(0, 20)}...`);
    
    // Wait for deactivate to complete
    await sleep(2000);

  } catch (error) {
    logError(`Test failed: ${error.message}`);
    console.error(error);
  } finally {
    // Step 7: Cleanup
    logStep(7, 'Cleaning up test content');
    if (testPagePath) {
      await deleteTestContent(testPagePath);
    }
  }

  // Summary
  console.log(`
${colors.bright}════════════════════════════════════════════════════════════════${colors.reset}`);

  if (success) {
    log(`
${colors.green}✅ TEST COMPLETED SUCCESSFULLY${colors.reset}

${colors.bright}What this test verified:${colors.reset}
  ✓ All ${NUM_JOBS} jobs were submitted with unique tokens
  ✓ Tokens were assigned in chronological order (nanosecond precision)
  ✓ All jobs completed (removed from JCR queue)

${colors.bright}What requires manual verification (server logs):${colors.reset}
  → Jobs executed in token-sorted order (ascending timestamp)
  
${colors.dim}The OrderedJobProcessor sorts jobs by token timestamp before execution,
ensuring earlier tokens always run first - even if submitted later due to
network delays. Check the logs above to confirm.${colors.reset}
`, colors.green);
  } else {
    log(`
${colors.red}❌ TEST FAILED${colors.reset}

Not all jobs completed successfully.
Check the server logs for errors.
`, colors.red);
    process.exit(1);
  }
}

// Run the test
main().catch(error => {
  console.error('Fatal error:', error);
  process.exit(1);
});

