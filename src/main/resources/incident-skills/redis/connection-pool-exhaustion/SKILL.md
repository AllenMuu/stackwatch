---
id: redis/connection-pool-exhaustion
version: 1.0.0
signals:
  - redis
  - pool
  - exhausted
toolsets:
  - logs
  - trace
---
# Redis connection-pool exhaustion

Review fixed-scope Logs and Trace observations for pool exhaustion and latency signals. Record
missing evidence when a source fails; never use credentials, shell commands, SQL, or Kubernetes
commands to investigate.
