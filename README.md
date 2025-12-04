# Guarded Job Management for AEM

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A distributed job processing system for Adobe Experience Manager (AEM) that guarantees **ordered execution** of jobs, even when submitted from multiple machines with network delays.

**Author:** Patrique Legault

## 🎯 The Problem

In distributed systems, you often need to process jobs **in the order they were initiated**, not the order they arrived at the server. Network delays can cause jobs to arrive out of order:

```
Machine A creates Job 1 at 10:00:00.001 ──────────────[delayed]──────────────▶ Arrives 10:00:00.150
Machine B creates Job 2 at 10:00:00.050 ───▶ Arrives 10:00:00.055
```

Without ordering guarantees, Job 2 would be processed before Job 1, even though Job 1 was created first. This system solves that problem.

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                       HTTP Layer (Any AEM Instance)                           │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐               │
│  │ JobSubmitServlet│  │ JobStatusServlet│  │ JobListServlet  │               │
│  │ POST .submit    │  │ GET .status     │  │ GET .list       │               │
│  └────────┬────────┘  └─────────────────┘  └─────────────────┘               │
└───────────┼──────────────────────────────────────────────────────────────────┘
            │
            ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                      JCR Persistence (Shared Storage)                         │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │                    JcrJobPersistenceService                            │  │
│  │  /var/guarded-jobs/{sling-id}/{year}/{month}/{day}/{job-id}           │  │
│  │    • topic, token, jobName, parameters (JSON blob)                    │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
            │
            ▼ (polled by leader every jobPollIntervalMs)
┌──────────────────────────────────────────────────────────────────────────────┐
│                      Leader Instance Only (Processing)                        │
│  ┌──────────────────────────┐      ┌─────────────────────────────────────┐   │
│  │  GuardedOrderTokenService│      │        OrderedJobProcessor          │   │
│  │  ┌────────────────────┐  │      │  1. Poll all jobs from JCR          │   │
│  │  │ GuardedOrderToken  │  │      │  2. Sort by token timestamp         │   │
│  │  │ • generate()       │  │      │  3. Execute per topic (sequential)  │   │
│  │  │ • isValid()        │◀─┼──────│  4. Delete from JCR on complete     │   │
│  │  │ • extractTimestamp │  │      │  ┌─────────────────────────────┐    │   │
│  │  │ • HMAC-SHA256 sign │  │      │  │  ClusterLeaderService       │    │   │
│  │  └────────────────────┘  │      │  │  (Sling Discovery API)      │    │   │
│  └──────────────────────────┘      │  └─────────────────────────────┘    │   │
│                                    └─────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────┘
            │
            ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                            Job Implementations                                │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐               │
│  │    EchoJob      │  │  EmptyGuardedJob│  │  Your Custom    │               │
│  │   "echo"        │  │    "empty"      │  │     Jobs...     │               │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘               │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Job Processing Flow

1. **Submit** (any instance): HTTP request → Generate token → Persist to JCR → Return "submitted"
2. **Poll** (leader only): Every `jobPollIntervalMs`, leader loads all pending jobs from JCR
3. **Sort**: Jobs sorted globally by token timestamp (ensures correct ordering)
4. **Execute**: Jobs processed sequentially per topic, parallel across different topics
5. **Cleanup**: Job deleted from JCR after execution (success or failure)

## 🔑 Key Concepts

### Guarded Order Token

A tamper-proof token that encodes **when** a job was created:

```
1733325600001234567.kX9mQzR8vN2pL4hY7wF3...
└──────┬──────────┘ └──────────┬──────────┘
    timestamp          HMAC-SHA256 signature
    (nanosecond       (prevents tampering)
     precision)
```

- **Monotonic timestamps**: Guarantees strictly increasing values
- **HMAC-SHA256 signature**: Any modification invalidates the token
- **Shared secret**: All AEM instances use the same key (via OSGi config)

### Topics

Jobs are organized into **topics** (logical queues). Each topic:
- Processes jobs **sequentially** in token order
- Is **independent** from other topics
- Has its own single-threaded executor

```
Topic: "asset-processing"     Topic: "page-publishing"
    │                              │
    ├─▶ Job A (token: 100)         ├─▶ Job X (token: 105)
    ├─▶ Job B (token: 200)         ├─▶ Job Y (token: 110)
    └─▶ Job C (token: 300)         └─▶ Job Z (token: 115)
         │                              │
         ▼ processed in order           ▼ processed in order
       A → B → C                      X → Y → Z
                                      (independently)
```

### Coalesce Timing

To handle network delays, the processor **waits briefly** after receiving a job before starting to process:

```
Time ─────────────────────────────────────────────────────▶

  Job 1 arrives ──┐
                  │    ┌── Coalesce window (50ms default)
  Job 3 arrives ──┼────┤
                  │    │
  Job 2 arrives ──┘    │
                       │
                       └──▶ Processing starts
                            Jobs sorted: 1, 2, 3
                            Executed: 1 → 2 → 3 ✓
```

## 📦 Components

### GuardedOrderToken
Generates and validates tamper-proof ordering tokens.

```java
GuardedOrderToken token = new GuardedOrderToken("secret-key");
String t1 = token.generate();  // "1733325600001.kX9mQz..."
boolean valid = token.isValid(t1);  // true
```

### GuardedOrderTokenService
OSGi service wrapper around `GuardedOrderToken` with configuration.

### GuardedJob<T>
Interface for jobs that can be processed. Implement this to create custom jobs:

```java
@Component(service = GuardedJob.class)
public class PublishPageJob implements GuardedJob<String> {
    
    @Reference
    private ReplicationService replicationService;
    
    @Override
    public String getName() {
        return "publish-page";
    }
    
    @Override
    public String execute(Map<String, Object> parameters) throws Exception {
        String path = (String) parameters.get("path");
        replicationService.replicate(path);
        return "Published: " + path;
    }
}
```

### JobProcessor / OrderedJobProcessor
Orchestrates job submission and ordered execution:
- **Submit**: Persists job to JCR and returns immediately (fire-and-forget)
- **Poll**: Leader instance polls JCR at configured intervals
- **Execute**: Processes jobs sequentially per topic, with configurable timeout
- **Cleanup**: Removes jobs from JCR after execution

## 🚀 HTTP API

### Submit a Job

```bash
POST /bin/guards/job.submit.json
Content-Type: application/json

{
    "topic": "my-topic",
    "jobName": "echo",
    "parameters": {
        "message": "Hello, world!"
    }
}
```

**Response:**
```json
{
    "success": true,
    "token": "1733325600001234567.kX9mQzR8vN2pL4hY7wF3...",
    "topic": "my-topic",
    "jobName": "echo",
    "message": "Job submitted successfully"
}
```

### Check Status

```bash
# All topics
GET /bin/guards/job.status.json

# Specific topic
GET /bin/guards/job.status.json?topic=my-topic
```

**Response:**
```json
{
    "topic": "my-topic",
    "pendingCount": 3,
    "processorShutdown": false
}
```

### List Available Jobs

```bash
GET /bin/guards/job.list.json
```

**Response:**
```json
{
    "jobs": [
        { "name": "echo", "className": "com.adobe.aem.support.core.guards.jobs.EchoJob" },
        { "name": "empty", "className": "com.adobe.aem.support.core.guards.jobs.EmptyGuardedJob" }
    ]
}
```

## ⚙️ Configuration

### GuardedOrderTokenServiceImpl

Configure the shared secret key for token signing:

**OSGi Config:** `com.adobe.aem.support.core.guards.token.impl.GuardedOrderTokenServiceImpl.cfg.json`

```json
{
    "secretKey": "$[env:GUARDED_TOKEN_SECRET_KEY]"
}
```

> ⚠️ **Important:** Use environment variables for the secret key in production!

### OrderedJobProcessor

Configure the coalesce timing and job timeout:

**OSGi Config:** `com.adobe.aem.support.core.guards.service.impl.OrderedJobProcessor.cfg.json`

```json
{
    "coalesceTimeMs": 50,
    "jobTimeoutSeconds": 30,
    "jobPollIntervalMs": 1000
}
```

| Property | Default | Description |
|----------|---------|-------------|
| `coalesceTimeMs` | 50 | Milliseconds to wait for more jobs before processing starts |
| `jobTimeoutSeconds` | 30 | Maximum time (in seconds) a job can run before being cancelled. Set to 0 to disable. |
| `jobPollIntervalMs` | 1000 | How often the leader polls JCR for new jobs (in milliseconds) |

#### Understanding `coalesceTimeMs`

This setting controls how long the processor waits after receiving a job before starting to process the queue. This is **critical for distributed ordering**.

**Why it matters:**
```
Machine A: Job created at T=0ms  ──[network delay 80ms]──▶  Arrives at T=80ms
Machine B: Job created at T=50ms ──[fast network]────────▶  Arrives at T=55ms
```

Without coalescing, Job B would process first (arrived first), even though Job A was created earlier. The coalesce window gives time for delayed jobs to arrive.

**Tuning guidelines:**

| Value | Use Case |
|-------|----------|
| `0` | Single machine only, no network delays expected |
| `20-50` | Local network, low latency between machines |
| `50-100` | **Recommended default** — handles typical network variability |
| `100-500` | High-latency networks, geographically distributed systems |
| `500+` | Very unreliable networks (use with caution — adds latency to all jobs) |

**Trade-off:** Higher values = better ordering accuracy but slower job start time.

#### Understanding `jobTimeoutSeconds`

This setting protects against jobs that run too long, preventing queue bottlenecks and memory issues.

**Why it matters:**
- A stuck job blocks all other jobs in the same topic
- Long-running jobs hold references, increasing heap usage
- Without timeout, a single bad job can halt an entire topic indefinitely

**Tuning guidelines:**

| Value | Use Case |
|-------|----------|
| `0` | Disable timeout (not recommended for production) |
| `10-30` | Quick operations: cache invalidation, notifications |
| `30-60` | **Recommended default** — standard operations |
| `60-300` | Content processing, asset transformations |
| `300+` | Long-running imports, bulk operations (consider breaking into smaller jobs) |

**Trade-off:** Lower values = faster failure detection but risk cancelling legitimate long operations.

#### Job Timeout Protection

To prevent queue bottlenecking and high heap usage from long-running or stuck jobs, the processor enforces a configurable timeout:

- Jobs exceeding the timeout are **automatically cancelled**
- A **WARN log** is emitted with details about the cancelled job
- The job's `CompletableFuture` completes exceptionally with a `TimeoutException`
- Other jobs in the queue continue processing normally

Example log message:
```
WARN  Job 'slow-task' in topic 'my-topic' cancelled after 30 seconds (timeout: 30s). 
      This may indicate a long-running or stuck job that could cause queue bottlenecking and high heap usage.
```

### JcrJobPersistenceService

Job persistence is the **core mechanism** for distributed job processing. All jobs are persisted to JCR, ensuring durability across JVM restarts and global ordering across all AEM instances.

> **Note:** Jobs are always stored at `/var/guarded-jobs` using the `guarded-job-service` service user. This is not configurable to ensure consistent behavior across all instances.

#### How It Works (Distributed Architecture)

Jobs flow through a distributed pipeline that ensures **global ordering** across all AEM instances:

```
┌─────────────────────────────────────────────────────────────────┐
│                     ANY AEM INSTANCE                             │
│  HTTP Request ──▶ Persist to JCR ──▶ Return "submitted"         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                        ┌──────────┐
                        │   JCR    │  (Shared Storage)
                        │ /var/... │
                        └──────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   LEADER INSTANCE ONLY                           │
│  Poll JCR ──▶ Sort by token ──▶ Process in order ──▶ Delete     │
└─────────────────────────────────────────────────────────────────┘
```

**Why only the leader processes:**
- Ensures **global ordering** across all instances
- Two jobs submitted to different instances will be processed in token order
- Prevents race conditions where instances could process the same job

**On JVM restart:**
- Leader polls JCR and picks up any unprocessed jobs
- Jobs are processed in correct token order

**Storage structure:**
```
/var/guarded-jobs/
  {sling-id}/                              # Instance that created the job
    2024/                                  # Year
      12/                                  # Month
        04/                                # Day
          550e8400-e29b-41d4-a716-446655440000/
            - jcr:mixinTypes: [gjm:GuardedJob]
            - gjm:topic: "my-topic"
            - gjm:tokenTimestamp: 1733325600001 (Long, indexed)
            - gjm:tokenSignature: "kX9mQz..." (String)
            - gjm:jobName: "echo"
            - persistedAt: 1733325600000
            - parameters: (binary JSON blob)
```

#### Custom Mixin Node Type

Jobs use a custom mixin `gjm:GuardedJob` (registered via repoinit) which:
- Defines typed properties for job data
- Enables efficient Oak index queries
- Ensures data integrity with mandatory properties

```cnd
<gjm = 'http://guarded-job-management.aem.adobe.com/1.0'>

[gjm:GuardedJob] > mix:created
  mixin
  - gjm:tokenTimestamp (long) mandatory
  - gjm:tokenSignature (string)
  - gjm:topic (string) mandatory
  - gjm:jobName (string) mandatory
```

#### Oak Index

A dedicated Lucene index (`gjmGuardedJobIndex`) is deployed to `/oak:index/` for efficient querying:
- Indexes `gjm:GuardedJob` mixin nodes only
- Supports ordering by `gjm:tokenTimestamp`
- Scoped to `/var/guarded-jobs` path
- Uses async indexing for minimal write impact

#### Query-Based Loading

Jobs are loaded using a JCR SQL2 query that:
- Queries by `gjm:GuardedJob` mixin type for index utilization
- Returns jobs ordered by `gjm:tokenTimestamp` (ascending)
- Uses Oak's `OPTION(LIMIT x)` for efficient database-level limiting
- Limits results to 100 jobs per poll

```sql
SELECT * FROM [gjm:GuardedJob] AS job
WHERE ISDESCENDANTNODE(job, '/var/guarded-jobs')
ORDER BY job.[gjm:tokenTimestamp] ASC
OPTION(LIMIT 100)
```

This ensures the system can handle large job backlogs without memory issues. See [Oak Query Options](https://jackrabbit.apache.org/oak/docs/query/query-engine.html#query-option-offset-limit) for more details.

> **Note:** Jobs are organized by date to prevent large node trees. Only the **cluster leader** can recover and process persisted jobs on startup.

#### Cluster Leadership

The `ClusterLeaderService` determines which AEM instance is the leader using the Sling Discovery API:

```java
@Reference
private ClusterLeaderService clusterLeaderService;

public void doLeaderOnlyWork() {
    if (!clusterLeaderService.isLeader()) {
        return; // Not the leader, skip
    }
    // Perform work that should only run on one instance
}
```

- In **single-instance** deployments: always returns `true`
- In **clustered** deployments: only one instance returns `true`
- Leadership can change dynamically when instances join/leave the cluster

#### Automatic Setup via Repo Init

The service user and permissions are automatically configured via Sling Repository Initializer:

**Repo Init Script:** `org.apache.sling.jcr.repoinit.RepositoryInitializer~guarded-job-management.cfg.json`

```
# Create the service user for job persistence
create service user guarded-job-service with path system/guarded-job-management

# Create the storage path for persisted jobs
create path (sling:Folder) /var/guarded-jobs

# Grant the service user full access to the storage path
set ACL for guarded-job-service
    allow jcr:all on /var/guarded-jobs
end
```

**Service User Mapping:**

`org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-guarded-job-management.cfg.json`
```json
{
    "user.mapping": [
        "guarded-job-management.core:guarded-job-service=[guarded-job-service]"
    ]
}
```

No manual setup required — just deploy the package and jobs will be automatically persisted and processed!

## 🧪 Testing with Scripts

A Node.js script is included for testing:

```bash
cd scripts
npm install  # if needed
node submit-jobs.mjs
```

This will:
1. Submit multiple jobs to different topics
2. Show a live progress table
3. Wait for all jobs to complete

**Sample output:**
```
🚀 Job Submission Script

Server: http://localhost:4502
──────────────────────────────────────────────────
Available jobs: echo, empty
──────────────────────────────────────────────────

Submitting 5 echo jobs to each of 3 topics...

  Submitted: 15 jobs ✓

Monitoring job completion...

┌──────────────────────────────────────────────────┐
│ Status                                           │
├────────────────────┬──────────────┬─────────────┤
│ Topic              │      Pending │ Progress    │
├────────────────────┼──────────────┼─────────────┤
│ topic-alpha        │            2 │ ██████░░░░ │
│ topic-beta         │            0 │ ██████████ │
│ topic-gamma        │            1 │ ████████░░ │
├────────────────────┴──────────────┴─────────────┤
│ Elapsed: 1.5s                                    │
│ Total completed: 12/15                           │
└──────────────────────────────────────────────────┘

✅ All jobs completed in 2.3s!
```

## 📁 Project Structure

```
guarded-job-management/
├── core/
│   └── src/main/java/com/adobe/aem/support/core/guards/
│       ├── token/
│       │   ├── GuardedOrderToken.java           # Token generation/validation
│       │   ├── GuardedOrderTokenService.java    # OSGi service interface
│       │   └── impl/
│       │       └── GuardedOrderTokenServiceImpl.java
│       ├── service/
│       │   ├── GuardedJob.java                  # Job interface
│       │   ├── JobProcessor.java                # Processor interface
│       │   ├── OrderedJobQueue.java             # Utility (not used in main flow)
│       │   └── impl/
│       │       └── OrderedJobProcessor.java     # Main processor (JCR-based)
│       ├── cluster/
│       │   ├── ClusterLeaderService.java        # Leadership detection interface
│       │   └── impl/
│       │       └── ClusterLeaderServiceImpl.java # Sling Discovery implementation
│       ├── persistence/
│       │   ├── JobPersistenceService.java       # Persistence interface
│       │   └── impl/
│       │       └── JcrJobPersistenceService.java # JCR implementation
│       ├── servlets/
│       │   ├── JobSubmitServlet.java            # POST .submit
│       │   ├── JobStatusServlet.java            # GET .status
│       │   └── JobListServlet.java              # GET .list
│       └── jobs/
│           ├── EchoJob.java                     # Example job
│           └── EmptyGuardedJob.java             # Minimal example
├── ui.config/
│   └── src/main/content/jcr_root/
│       ├── apps/.../osgiconfig/
│       │   ├── com.adobe.aem.support.core.guards.token.impl.GuardedOrderTokenServiceImpl.cfg.json
│       │   ├── com.adobe.aem.support.core.guards.service.impl.OrderedJobProcessor.cfg.json
│       │   ├── org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-guarded-job-management.cfg.json
│       │   └── org.apache.sling.jcr.repoinit.RepositoryInitializer~guarded-job-management.cfg.json
│       └── _oak_index/
│           └── gjmGuardedJobIndex/         # Oak Lucene index for job queries
├── scripts/
│   ├── submit-jobs.mjs                          # Test script
│   └── package.json
└── README.md
```

## 🔒 Security Considerations

1. **Secret Key**: Store in environment variables, never in code
2. **HMAC-SHA256**: Industry-standard signing algorithm
3. **Constant-time comparison**: Prevents timing attacks on signature validation
4. **Token expiration**: Consider adding TTL validation for production use

## 📈 Performance

- **Per-topic throughput**: Sequential by design (ordering guarantee)
- **Cross-topic throughput**: Fully parallel (independent executors)
- **Memory**: O(pending jobs) per topic
- **Coalesce tradeoff**: Higher values = better ordering accuracy, lower values = faster processing

## 🛠️ Building & Testing

### Prerequisites

- **Java 11** or higher
- **Maven 3.6+**
- **AEM 6.5** or **AEM as a Cloud Service** (for deployment)

### Build Commands

```bash
# Build all modules
mvn clean install

# Build without running tests
mvn clean install -DskipTests

# Build and deploy to local AEM author (localhost:4502)
mvn clean install -PautoInstallPackage

# Build and deploy to local AEM publish (localhost:4503)
mvn clean install -PautoInstallPackagePublish

# Build only the core bundle
mvn clean install -pl core
```

### Running Tests

```bash
# Run all tests
mvn test

# Run tests for core module only
mvn test -pl core

# Run a specific test class
mvn test -pl core -Dtest=GuardedOrderTokenTest

# Run tests with verbose output
mvn test -pl core -Dsurefire.useFile=false
```

### Test Coverage

The project includes unit tests for:
- `GuardedOrderToken` — Token generation, validation, and ordering
- `OrderedJobQueue` — Thread-safe queue operations
- `OrderedJobProcessor` — Job submission and ordered execution

### Integration Testing

After deploying to AEM, use the included Node.js script:

```bash
cd scripts
node submit-jobs.mjs
```

Or use curl to test individual endpoints:

```bash
# List available jobs
curl -u admin:admin http://localhost:4502/bin/guards/job.list.json

# Submit a job
curl -X POST http://localhost:4502/bin/guards/job.submit.json \
  -u admin:admin \
  -H "Content-Type: application/json" \
  -d '{"topic": "test", "jobName": "echo", "parameters": {"message": "Hello!"}}'

# Check status
curl -u admin:admin "http://localhost:4502/bin/guards/job.status.json?topic=test"
```

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📝 License

This project is licensed under the **Apache License 2.0** — see the [LICENSE](LICENSE) file for details.

```
Copyright 2024 Patrique Legault

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
