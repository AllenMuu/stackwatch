---
id: spring/feign-timeout
version: 1.0.0
signals:
  - feign
  - timeout
toolsets:
  - logs
  - trace
  - git-deployment
---
# Spring Feign timeout

Inspect redacted Logs and Trace observations for the downstream timeout, then compare the fixed
Git/Deployment observation with the incident window. Treat the result as provisional until two
independent sources support the hypothesis. Do not execute remediation or issue commands.
