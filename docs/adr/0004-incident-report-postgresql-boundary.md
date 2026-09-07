# Separate Incident reporting from Fast Path RCA

The existing RootCauseAnalysis remains the compatible Fast Path output. Deep Investigation consumes the completed Error Cluster and persists its own Incident Report, including hypotheses, structured evidence references, and missing evidence, in a Flyway-managed `stackwatch_incident` PostgreSQL schema. The Incident feature is independently disabled by default and may share a PostgreSQL instance with L2 without sharing an enablement flag.
