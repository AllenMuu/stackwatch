CREATE SCHEMA IF NOT EXISTS stackwatch_incident;

CREATE TABLE stackwatch_incident.incidents (
    id UUID PRIMARY KEY,
    application_name VARCHAR(255) NOT NULL,
    environment VARCHAR(128) NOT NULL,
    cluster_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failure_reason TEXT
);

CREATE UNIQUE INDEX active_incidents_by_identity
    ON stackwatch_incident.incidents (application_name, environment, cluster_id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE TABLE stackwatch_incident.triggers (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES stackwatch_incident.incidents (id),
    trigger_type VARCHAR(64) NOT NULL,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX triggers_by_incident ON stackwatch_incident.triggers (incident_id, created_at);

CREATE TABLE stackwatch_incident.steps (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES stackwatch_incident.incidents (id),
    sequence_number INTEGER NOT NULL,
    decision_type VARCHAR(64) NOT NULL,
    decision_summary TEXT NOT NULL,
    toolset VARCHAR(64),
    outcome VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (incident_id, sequence_number)
);

CREATE TABLE stackwatch_incident.observations (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES stackwatch_incident.incidents (id),
    step_id UUID REFERENCES stackwatch_incident.steps (id),
    source_type VARCHAR(64) NOT NULL,
    status VARCHAR(64) NOT NULL,
    redacted_summary TEXT NOT NULL,
    provenance VARCHAR(512) NOT NULL,
    observed_from TIMESTAMPTZ,
    observed_to TIMESTAMPTZ,
    content_hash VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX observations_by_incident ON stackwatch_incident.observations (incident_id, created_at);

CREATE TABLE stackwatch_incident.evidence (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES stackwatch_incident.incidents (id),
    observation_id UUID NOT NULL REFERENCES stackwatch_incident.observations (id),
    source_type VARCHAR(64) NOT NULL,
    redacted_summary TEXT NOT NULL,
    provenance VARCHAR(512) NOT NULL,
    observed_from TIMESTAMPTZ,
    observed_to TIMESTAMPTZ,
    content_hash VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX evidence_by_incident ON stackwatch_incident.evidence (incident_id, created_at);

CREATE TABLE stackwatch_incident.hypotheses (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES stackwatch_incident.incidents (id),
    statement TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    confidence DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX hypotheses_by_incident ON stackwatch_incident.hypotheses (incident_id, created_at);

CREATE TABLE stackwatch_incident.reports (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL UNIQUE REFERENCES stackwatch_incident.incidents (id),
    recommendation TEXT,
    missing_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    review_outcome VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
