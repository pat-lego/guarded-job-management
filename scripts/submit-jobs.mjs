#!/usr/bin/env node

/**
 * Script to submit Echo jobs to the AEM job processor, validate job details, and monitor completion.
 * 
 * Usage: node submit-jobs.mjs
 * 
 * VS Code: Install "Code Runner" extension, then right-click -> "Run Code"
 */

const BASE_URL = 'http://localhost:4502';
const AUTH = 'admin:admin';
const POLL_INTERVAL_MS = 500;

function getAuthHeader() {
  return 'Basic ' + Buffer.from(AUTH).toString('base64');
}

async function submitJob(topic, jobName, parameters) {
  const url = `${BASE_URL}/bin/guards/job.submit.json`;
  
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': getAuthHeader()
      },
      body: JSON.stringify({ topic, jobName, parameters })
    });
    
    const result = await response.json();
    return result;
  } catch (error) {
    return { success: false, error: error.message };
  }
}

async function listJobs() {
  const url = `${BASE_URL}/bin/guards/job.list.json`;
  try {
    const response = await fetch(url, {
      headers: { 'Authorization': getAuthHeader() }
    });
    return await response.json();
  } catch (error) {
    return { error: error.message };
  }
}

async function getStatus(topic) {
  const url = topic 
    ? `${BASE_URL}/bin/guards/job.status.json?topic=${encodeURIComponent(topic)}`
    : `${BASE_URL}/bin/guards/job.status.json`;
  try {
    const response = await fetch(url, {
      headers: { 'Authorization': getAuthHeader() }
    });
    return await response.json();
  } catch (error) {
    return { error: error.message };
  }
}

/**
 * Get detailed job information for a topic.
 * @param {string} topic - The topic to query
 * @param {number} limit - Maximum number of jobs to return (default: 100)
 * @returns {Promise<{topic: string, count: number, jobs: Array}>}
 */
async function getJobDetails(topic, limit = 100) {
  const url = `${BASE_URL}/bin/guards/job.details.json?topic=${encodeURIComponent(topic)}&limit=${limit}`;
  try {
    const response = await fetch(url, {
      headers: { 'Authorization': getAuthHeader() }
    });
    return await response.json();
  } catch (error) {
    return { error: error.message };
  }
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function formatDuration(ms) {
  if (ms < 1000) return `${ms}ms`;
  const seconds = (ms / 1000).toFixed(1);
  return `${seconds}s`;
}

function clearLines(count) {
  for (let i = 0; i < count; i++) {
    process.stdout.write('\x1b[1A\x1b[2K');
  }
}

function renderStatusTable(topics, statusMap, submitted, completed, startTime) {
  const elapsed = Date.now() - startTime;
  const lines = [];
  
  lines.push(`┌${'─'.repeat(50)}┐`);
  lines.push(`│ ${'Status'.padEnd(48)} │`);
  lines.push(`├${'─'.repeat(20)}┬${'─'.repeat(14)}┬${'─'.repeat(13)}┤`);
  lines.push(`│ ${'Topic'.padEnd(18)} │ ${'Pending'.padEnd(12)} │ ${'Progress'.padEnd(11)} │`);
  lines.push(`├${'─'.repeat(20)}┼${'─'.repeat(14)}┼${'─'.repeat(13)}┤`);
  
  for (const topic of topics) {
    const status = statusMap[topic] || { pendingCount: 0 };
    const pending = status.pendingCount ?? 0;
    const topicSubmitted = submitted[topic] || 0;
    const done = topicSubmitted - pending;
    const progress = topicSubmitted > 0 ? Math.round((done / topicSubmitted) * 100) : 100;
    const bar = '█'.repeat(Math.floor(progress / 10)) + '░'.repeat(10 - Math.floor(progress / 10));
    
    lines.push(`│ ${topic.padEnd(18)} │ ${String(pending).padStart(12)} │ ${bar} │`);
  }
  
  lines.push(`├${'─'.repeat(20)}┴${'─'.repeat(14)}┴${'─'.repeat(13)}┤`);
  lines.push(`│ Elapsed: ${formatDuration(elapsed).padEnd(39)} │`);
  lines.push(`│ Total completed: ${completed}/${Object.values(submitted).reduce((a, b) => a + b, 0)}`.padEnd(51) + '│');
  lines.push(`└${'─'.repeat(50)}┘`);
  
  return lines;
}

async function monitorUntilComplete(topics, submitted, startTime) {
  let lastLineCount = 0;
  let allComplete = false;
  
  while (!allComplete) {
    const statusMap = {};
    let totalPending = 0;
    
    for (const topic of topics) {
      const status = await getStatus(topic);
      statusMap[topic] = status;
      totalPending += status.pendingCount ?? 0;
    }
    
    const totalSubmitted = Object.values(submitted).reduce((a, b) => a + b, 0);
    const completed = totalSubmitted - totalPending;
    
    // Clear previous output and render new status
    if (lastLineCount > 0) {
      clearLines(lastLineCount);
    }
    
    const lines = renderStatusTable(topics, statusMap, submitted, completed, startTime);
    console.log(lines.join('\n'));
    lastLineCount = lines.length;
    
    if (totalPending === 0) {
      allComplete = true;
    } else {
      await sleep(POLL_INTERVAL_MS);
    }
  }
  
  return Date.now() - startTime;
}

/**
 * Validate job details response structure and data.
 */
function validateJobDetails(details, expectedTopic) {
  const errors = [];
  
  // Validate response structure
  if (typeof details.topic !== 'string') {
    errors.push('Missing or invalid "topic" field');
  } else if (details.topic !== expectedTopic) {
    errors.push(`Topic mismatch: expected "${expectedTopic}", got "${details.topic}"`);
  }
  
  if (typeof details.count !== 'number') {
    errors.push('Missing or invalid "count" field');
  }
  
  if (!Array.isArray(details.jobs)) {
    errors.push('Missing or invalid "jobs" array');
    return errors;
  }
  
  // Validate each job
  for (let i = 0; i < details.jobs.length; i++) {
    const job = details.jobs[i];
    const prefix = `Job[${i}]`;
    
    if (typeof job.token !== 'string' || !job.token.includes('.')) {
      errors.push(`${prefix}: Invalid token format`);
    }
    
    if (typeof job.jobName !== 'string' || job.jobName.length === 0) {
      errors.push(`${prefix}: Missing jobName`);
    }
    
    if (typeof job.submittedBy !== 'string' || job.submittedBy.length === 0) {
      errors.push(`${prefix}: Missing submittedBy`);
    }
    
    if (typeof job.persistedAt !== 'string') {
      errors.push(`${prefix}: Missing persistedAt`);
    }
    
    if (typeof job.persistenceId !== 'string' || !job.persistenceId.startsWith('/var/guarded-jobs')) {
      errors.push(`${prefix}: Invalid persistenceId`);
    }
  }
  
  return errors;
}

/**
 * Print job details in a formatted table.
 */
function printJobDetails(details) {
  console.log(`\n┌${'─'.repeat(90)}┐`);
  console.log(`│ ${'Job Details for topic: ' + details.topic}`.padEnd(90) + '│');
  console.log(`│ ${'Count: ' + details.count}`.padEnd(90) + '│');
  console.log(`├${'─'.repeat(90)}┤`);
  console.log(`│ ${'Token'.padEnd(25)} │ ${'Job'.padEnd(10)} │ ${'User'.padEnd(12)} │ ${'Persisted At'.padEnd(25)} │`);
  console.log(`├${'─'.repeat(90)}┤`);
  
  for (const job of details.jobs) {
    const tokenShort = job.token.length > 23 ? job.token.substring(0, 20) + '...' : job.token;
    const persistedAt = job.persistedAt.length > 23 ? job.persistedAt.substring(0, 23) : job.persistedAt;
    console.log(`│ ${tokenShort.padEnd(25)} │ ${job.jobName.padEnd(10)} │ ${job.submittedBy.padEnd(12)} │ ${persistedAt.padEnd(25)} │`);
  }
  
  console.log(`└${'─'.repeat(90)}┘`);
}

async function main() {
  console.log('🚀 Job Submission & Validation Script\n');
  console.log(`Server: ${BASE_URL}`);
  console.log('─'.repeat(50));
  
  // List available jobs first
  const jobList = await listJobs();
  if (jobList.jobs) {
    console.log('Available jobs:', jobList.jobs.map(j => j.name).join(', '));
  } else {
    console.log('Available jobs: (unable to fetch)');
  }
  console.log('─'.repeat(50));
  
  // Configuration
  const topics = ['topic-alpha', 'topic-beta', 'topic-gamma'];
  const jobsPerTopic = 5;
  
  console.log(`\nSubmitting ${jobsPerTopic} echo jobs to each of ${topics.length} topics...\n`);
  
  const submitted = {};
  const submittedTokens = {};
  const startTime = Date.now();
  
  // Submit all jobs and capture tokens
  const submissions = [];
  for (const topic of topics) {
    submitted[topic] = 0;
    submittedTokens[topic] = [];
    
    for (let i = 1; i <= jobsPerTopic; i++) {
      submissions.push(
        submitJob(topic, 'echo', { 
          message: `Hello from ${topic} - job #${i}`,
          delay: Math.floor(Math.random() * 500) + 100 // Random delay 100-600ms to simulate work
        }).then(result => {
          if (result.success) {
            submitted[topic]++;
            submittedTokens[topic].push(result.token);
            process.stdout.write(`\r  Submitted: ${Object.values(submitted).reduce((a, b) => a + b, 0)} jobs`);
          }
          return result;
        })
      );
    }
  }
  
  await Promise.all(submissions);
  const totalSubmitted = Object.values(submitted).reduce((a, b) => a + b, 0);
  console.log(`\r  Submitted: ${totalSubmitted} jobs ✓\n`);
  
  // === VALIDATION: Query and validate job details immediately after submission ===
  console.log('─'.repeat(50));
  console.log('🔍 Validating Job Details API...\n');
  
  let validationPassed = true;
  
  for (const topic of topics) {
    const details = await getJobDetails(topic, 100);
    
    if (details.error) {
      console.log(`❌ ${topic}: Failed to fetch - ${details.error}`);
      validationPassed = false;
      continue;
    }
    
    const errors = validateJobDetails(details, topic);
    
    if (errors.length > 0) {
      console.log(`❌ ${topic}: Validation failed`);
      errors.forEach(err => console.log(`   - ${err}`));
      validationPassed = false;
    } else {
      console.log(`✅ ${topic}: ${details.count} jobs found, all fields valid`);
      
      // Verify submittedBy is the authenticated user
      const allByAdmin = details.jobs.every(j => j.submittedBy === 'admin');
      if (allByAdmin) {
        console.log(`   ✓ All jobs submitted by 'admin'`);
      } else {
        console.log(`   ⚠ Some jobs have unexpected submittedBy values`);
      }
      
      // Verify tokens match what we submitted
      const detailTokens = details.jobs.map(j => j.token);
      const matchingTokens = submittedTokens[topic].filter(t => detailTokens.includes(t));
      console.log(`   ✓ ${matchingTokens.length}/${submittedTokens[topic].length} submitted tokens found in details`);
    }
  }
  
  // Print detailed view for first topic
  const firstTopicDetails = await getJobDetails(topics[0], 10);
  if (firstTopicDetails.jobs && firstTopicDetails.jobs.length > 0) {
    printJobDetails(firstTopicDetails);
  }
  
  console.log('─'.repeat(50));
  
  if (validationPassed) {
    console.log('\n✅ Job Details API validation PASSED\n');
  } else {
    console.log('\n❌ Job Details API validation FAILED\n');
  }
  
  // === Monitor job completion ===
  console.log('Monitoring job completion...\n');
  
  const duration = await monitorUntilComplete(topics, submitted, startTime);
  
  console.log(`\n✅ All jobs completed in ${formatDuration(duration)}!`);
  
  // Final verification: jobs should be removed after completion
  console.log('\n─'.repeat(50));
  console.log('🔍 Verifying jobs are removed after completion...\n');
  
  await sleep(500); // Small delay to ensure cleanup
  
  let allCleared = true;
  for (const topic of topics) {
    const details = await getJobDetails(topic, 100);
    if (details.count > 0) {
      console.log(`⚠ ${topic}: ${details.count} jobs still pending (may be processing)`);
      allCleared = false;
    } else {
      console.log(`✅ ${topic}: All jobs completed and removed`);
    }
  }
  
  console.log('─'.repeat(50));
  
  if (allCleared) {
    console.log('\n🎉 All validations passed! Job lifecycle working correctly.\n');
  } else {
    console.log('\n⚠ Some jobs may still be processing. This is normal if jobs have delays.\n');
  }
}

main().catch(console.error);
