---
layout: home
title: StackWatch

hero:
  name: StackWatch
  text: Production errors, localized at the root.
  tagline: AI-driven root cause analysis for Java production errors — stacktrace fingerprint merging + LLM root cause localization + scheduled weekly digest. Cut mean time to localize incidents by ~40%.
  actions:
    - theme: brand
      text: Quick Start
      link: /guide/getting-started
    - theme: alt
      text: GitHub
      link: https://github.com/AllenMuu/stackwatch

features:
  - title: Three-tier cascade
    details: L1 fingerprint cache → L2 vector merge → L3 LLM root cause. Only ~1% of exceptions actually call the LLM — the cost ladder cuts analysis cost by two orders of magnitude.
  - title: Bidirectional feedback flywheel
    details: Developer confirmations flow back as few-shot positive samples and anti-pattern negative samples, both injected into the next L3 prompt. Every call gets smarter.
  - title: Zero-infrastructure start
    details: Starts with no DB, no Kafka, no vector store. L1/L3 work out of the box. L2 (PgVector) and Kafka unlock progressively via config flags.
---

<div class="landing">
  <section class="landing-section">
    <p class="landing-section-label">Architecture</p>
    <h2>Five layers. One pipeline.</h2>
    <div class="arch-map">
      <div class="arch-step">
        <span class="arch-step-num">01 / Collect</span>
        <div class="arch-step-body">
          <h3>Collector</h3>
          <p>Logback Appender · HTTP /collect · Kafka (off by default)</p>
        </div>
      </div>
      <div class="arch-step">
        <span class="arch-step-num">02 / Preprocess</span>
        <div class="arch-step-body">
          <h3>Fingerprinter</h3>
          <p>SHA-256 + framework-frame filtering · versioned fingerprints</p>
        </div>
      </div>
      <div class="arch-step">
        <span class="arch-step-num">03 / Analyze</span>
        <div class="arch-step-body">
          <h3>Analyzer — L1 / L2 / L3 cascade</h3>
          <p>Exact cache hit → vector merge → LLM root cause (core hub)</p>
        </div>
      </div>
      <div class="arch-step">
        <span class="arch-step-num">04 / Aggregate</span>
        <div class="arch-step-body">
          <h3>Aggregator</h3>
          <p>Real-time surge detection · weekly Top-N aggregation</p>
        </div>
      </div>
      <div class="arch-step">
        <span class="arch-step-num">05 / Notify</span>
        <div class="arch-step-body">
          <h3>Notifier</h3>
          <p>Feishu real-time alert · Feishu weekly report</p>
        </div>
      </div>
    </div>
  </section>

  <section class="landing-cta">
    <h2>Start where your errors are.</h2>
    <a class="cta-primary" href="/stackwatch/guide/getting-started">Read the docs →</a>
  </section>
</div>
