#!/usr/bin/env node

/**
 * Script to submit Echo jobs to the AEM job processor and monitor their completion.
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

async function main() {
  console.log('🚀 Job Submission Script\n');
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
  const startTime = Date.now();
  
  // Submit all jobs
  const submissions = [];
  for (const topic of topics) {
    submitted[topic] = 0;
    for (let i = 1; i <= jobsPerTopic; i++) {
      submissions.push(
        submitJob(topic, 'echo', { 
          message: `Hello from ${topic} - job #${i}`,
          delay: Math.floor(Math.random() * 500) + 100 // Random delay 100-600ms to simulate work
        }).then(result => {
          if (result.success) {
            submitted[topic]++;
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
  
  console.log('Monitoring job completion...\n');
  
  // Monitor until all complete
  const duration = await monitorUntilComplete(topics, submitted, startTime);
  
  console.log(`\n✅ All jobs completed in ${formatDuration(duration)}!`);
}

main().catch(console.error);
