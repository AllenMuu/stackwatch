---
layout: home
title: StackWatch

hero:
  name: StackWatch
  text: 生产异常，精准定位根因。
  tagline: AI 驱动的 Java 生产错误根因分析 - 异常堆栈指纹归并 + LLM 根因定位 + 定时周报聚合。生产故障平均定位耗时缩短约 40%。
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/guide/getting-started
    - theme: alt
      text: GitHub
      link: https://github.com/AllenMuu/stackwatch

features:
  - title: 三层级联归并
    details: L1 指纹缓存 -> L2 向量归并 -> L3 LLM 根因分析。仅约 1% 的异常真正调用 LLM，分析成本降两个数量级。
  - title: 正负双向反馈飞轮
    details: 研发确认回流为 few-shot 正样本与 anti-pattern 负样本，均注入下一次 L3 prompt。越用越准。
  - title: 零基础设施启动
    details: 默认无 DB、无 Kafka、无向量库即可启动。L1/L3 开箱即用，L2（PgVector）与 Kafka 按配置渐进解锁。
---

<div class="landing">
  <section class="landing-section">
    <p class="landing-section-label">架构</p>
    <h2>五层主链路，一条流水线。</h2>
    <div class="arch-map">
      <div class="arch-step">
        <span class="arch-step-num">01 / 采集</span>
        <div class="arch-step-body">
          <h3>Collector 采集层</h3>
          <p>Logback Appender · HTTP /collect · Kafka（默认关闭）</p>
        </div>
      </div>
      <div class="arch-step">
        <span class="arch-step-num">02 / 预处理</span>
        <div class="arch-step-body">
          <h3>Fingerprinter 指纹去重</h3>
          <p>SHA-256 + 框架帧过滤 · 指纹版本化</p>
        </div>
      </div>
      <div class="arch-step">
        <span class="arch-step-num">03 / 分析</span>
        <div class="arch-step-body">
          <h3>Analyzer 三层级联（核心）</h3>
          <p>指纹缓存命中 -> 向量归并 -> LLM 根因定位（系统中枢）</p>
        </div>
      </div>
      <div class="arch-step">
        <span class="arch-step-num">04 / 聚合</span>
        <div class="arch-step-body">
          <h3>Aggregator 聚合层</h3>
          <p>实时激增检测 · 按周聚合 Top N</p>
        </div>
      </div>
      <div class="arch-step">
        <span class="arch-step-num">05 / 投递</span>
        <div class="arch-step-body">
          <h3>Notifier 投递层</h3>
          <p>飞书实时告警 · 飞书周报定时推送</p>
        </div>
      </div>
    </div>
  </section>

  <section class="landing-cta">
    <h2>从你的错误开始。</h2>
    <a class="cta-primary" href="/stackwatch/zh/guide/getting-started">阅读文档 -></a>
  </section>
</div>
