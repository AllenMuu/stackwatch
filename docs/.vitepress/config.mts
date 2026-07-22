import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'StackWatch',
  description: 'AI-driven root cause analysis for Java production errors',
  base: '/stackwatch/',
  cleanUrls: true,
  lastUpdated: true,

  head: [
    ['meta', { name: 'theme-color', content: '#0a0a0a' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:title', content: 'StackWatch' }],
    ['meta', { property: 'og:description', content: 'AI-driven root cause analysis for Java production errors' }],
  ],

  locales: {
    root: {
      label: 'English',
      lang: 'en',
      themeConfig: {
        nav: [
          { text: 'Guide', link: '/guide/getting-started', activeMatch: '/guide/' },
          { text: 'Architecture', link: '/guide/architecture' },
          { text: 'GitHub', link: 'https://github.com/AllenMuu/stackwatch' },
        ],
        sidebar: {
          '/guide/': [
            {
              text: 'Getting Started',
              items: [{ text: 'Quick Start', link: '/guide/getting-started' }],
            },
            {
              text: 'Reference',
              items: [
                { text: 'Architecture', link: '/guide/architecture' },
                { text: 'Tech Selection', link: '/guide/tech-selection' },
                { text: 'Upgrade Path', link: '/guide/upgrade-path' },
              ],
            },
          ],
        },
        footer: {
          message: 'Released under the MIT License.',
          copyright: 'Copyright © 2024-present StackWatch',
        },
        outline: { label: 'On this page' },
        docFooter: { prev: 'Previous', next: 'Next' },
        lastUpdated: { text: 'Last updated' },
        returnToTopLabel: 'Back to top',
        sidebarMenuLabel: 'Menu',
        darkModeSwitchLabel: 'Appearance',
      },
    },
    zh: {
      label: '中文',
      lang: 'zh',
      link: '/zh/',
      themeConfig: {
        nav: [
          { text: '指南', link: '/zh/guide/getting-started', activeMatch: '/zh/guide/' },
          { text: '架构', link: '/zh/guide/architecture' },
          { text: 'GitHub', link: 'https://github.com/AllenMuu/stackwatch' },
        ],
        sidebar: {
          '/zh/guide/': [
            {
              text: '快速开始',
              items: [{ text: '快速上手', link: '/zh/guide/getting-started' }],
            },
            {
              text: '参考',
              items: [
                { text: '架构设计', link: '/zh/guide/architecture' },
                { text: '选型分析', link: '/zh/guide/tech-selection' },
                { text: '升级路径', link: '/zh/guide/upgrade-path' },
              ],
            },
          ],
        },
        footer: {
          message: '基于 MIT License 发布。',
          copyright: 'Copyright © 2024-present StackWatch',
        },
        outline: { label: '本页目录' },
        docFooter: { prev: '上一篇', next: '下一篇' },
        lastUpdated: { text: '最后更新' },
        returnToTopLabel: '回到顶部',
        sidebarMenuLabel: '菜单',
        darkModeSwitchLabel: '外观',
      },
    },
  },

  themeConfig: {
    socialLinks: [
      { icon: 'github', link: 'https://github.com/AllenMuu/stackwatch' },
    ],
    search: {
      provider: 'local',
    },
  },
})
