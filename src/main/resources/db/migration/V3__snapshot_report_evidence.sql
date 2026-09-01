CREATE TABLE stackwatch_incident.report_evidence (
    report_id UUID NOT NULL REFERENCES stackwatch_incident.reports (id) ON DELETE CASCADE,
    evidence_id UUID NOT NULL REFERENCES stackwatch_incident.evidence (id),
    PRIMARY KEY (report_id, evidence_id)
);

CREATE INDEX report_evidence_by_evidence
    ON stackwatch_incident.report_evidence (evidence_id);
