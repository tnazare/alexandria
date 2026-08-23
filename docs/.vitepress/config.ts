import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Alexandria',
  description: 'Open-source, self-hosted enterprise skill layer for AI-assisted software engineering',
  outDir: '.vitepress/dist',

  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/installation' },
      { text: 'Features', link: '/features/authentication' },
      { text: 'API Reference', link: '/api/overview' },
      { text: 'Troubleshooting', link: '/troubleshooting' }
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Installation', link: '/guide/installation' },
            { text: 'Configuration', link: '/guide/configuration' },
            { text: 'OIDC Setup', link: '/guide/oidc-setup' }
          ]
        },
        {
          text: 'Deployment',
          items: [
            { text: 'Local (Docker Compose)', link: '/guide/deployment-local' },
            { text: 'AWS (Production)', link: '/guide/deployment-aws' }
          ]
        }
      ],
      '/features/': [
        {
          text: 'Features',
          items: [
            { text: 'Authentication', link: '/features/authentication' },
            { text: 'User Management', link: '/features/users' },
            { text: 'Team Management', link: '/features/teams' },
            { text: 'Role-Based Access Control', link: '/features/rbac' },
            { text: 'Audit Logging', link: '/features/audit' },
            { text: 'Health Monitoring', link: '/features/health' }
          ]
        }
      ],
      '/api/': [
        {
          text: 'API Reference',
          items: [
            { text: 'Overview', link: '/api/overview' },
            { text: 'Users', link: '/api/users' },
            { text: 'Teams', link: '/api/teams' },
            { text: 'Audit Events', link: '/api/audit' }
          ]
        }
      ]
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/alexandria' }
    ],

    footer: {
      message: 'Released under the Apache 2.0 License.',
      copyright: 'Copyright © 2024 Alexandria Contributors'
    }
  }
})
