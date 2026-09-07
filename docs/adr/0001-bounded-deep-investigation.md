# Allow bounded Deep Investigation outside the Fast Path

StackWatch retains its single-step, synchronous Fast Path for routine exception analysis. A separately enabled Deep Investigation may use a bounded, multi-step, read-only Agent Loop and persist its audit trail in PostgreSQL, because an evidence-grounded incident investigation cannot be expressed as one LLM call. This does not authorize remediation, checkpoint/resume, a general workflow engine, or multi-Agent orchestration.
