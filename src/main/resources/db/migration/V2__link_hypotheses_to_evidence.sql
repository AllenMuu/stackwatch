CREATE TABLE stackwatch_incident.hypothesis_evidence (
    hypothesis_id UUID NOT NULL REFERENCES stackwatch_incident.hypotheses (id) ON DELETE CASCADE,
    evidence_id UUID NOT NULL REFERENCES stackwatch_incident.evidence (id),
    PRIMARY KEY (hypothesis_id, evidence_id)
);

CREATE INDEX hypothesis_evidence_by_evidence
    ON stackwatch_incident.hypothesis_evidence (evidence_id);
