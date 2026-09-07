# Persist and run Deep Investigations asynchronously

Deep Investigation is an opt-in PostgreSQL-backed capability. StackWatch creates or reuses one Active Incident for each application, environment, and Error Cluster, then runs its bounded investigation asynchronously so Fast Path analysis never waits for agent work. PostgreSQL is the system of record for Incident triggers, steps, evidence, and reports; the default Fast Path remains able to start without infrastructure.
