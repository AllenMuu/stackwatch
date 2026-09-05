CREATE SCHEMA IF NOT EXISTS stackwatch_error_history;

CREATE TABLE stackwatch_error_history.error_groups (
  id UUID PRIMARY KEY,
  app_name VARCHAR(255) NOT NULL,
  fingerprint_version VARCHAR(16) NOT NULL,
  strict_fingerprint CHAR(64) NOT NULL,
  loose_fingerprint CHAR(64) NOT NULL,
  outer_exception_type VARCHAR(500),
  effective_exception_type VARCHAR(500),
  message_template TEXT,
  normalized_frames JSONB,
  rca JSONB,
  cluster_id VARCHAR(255),
  occurrence_count BIGINT NOT NULL,
  first_seen TIMESTAMPTZ NOT NULL,
  last_seen TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_error_group_identity UNIQUE (app_name, fingerprint_version, strict_fingerprint)
);

CREATE TABLE stackwatch_error_history.accepted_error_events (
  app_name VARCHAR(255) NOT NULL,
  event_id VARCHAR(255) NOT NULL,
  group_id UUID NOT NULL,
  accepted_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (app_name, event_id),
  CONSTRAINT fk_accepted_error_group FOREIGN KEY (group_id)
    REFERENCES stackwatch_error_history.error_groups(id)
);
