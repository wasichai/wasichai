# Modules

Core is always installed. Every other module is one backend starter plus one frontend package, and an app adds it
by listing both. [Build your app](../guides/build-your-app.md) shows how.

| Module | What it adds | Backend | Frontend |
|---|---|---|---|
| [core](core.md) | objects, fields, records, relationships, identity, audit, admin | `wasichai-spring-boot-starter` | `@wasichai/core`, `@wasichai/ui` |
| [views](views.md) | saved list configurations | `wasichai-spring-boot-starter-views` | `@wasichai/views` |
| [forms](forms.md) | field arrangements in sections | `wasichai-spring-boot-starter-forms` | `@wasichai/forms` |
| [pages](pages.md) | metadata-defined pages and their builder | `wasichai-spring-boot-starter-pages` | `@wasichai/pages` |
| [workflow](workflow.md) | states and transitions on records | `wasichai-spring-boot-starter-workflow` | `@wasichai/workflow` |
| [automation](automation.md) | rules that react to record changes | `wasichai-spring-boot-starter-automation` | `@wasichai/automation` |
| [documents](documents.md) | document templates, issuing, printing | `wasichai-spring-boot-starter-documents` | `@wasichai/documents` |
| [gis](gis.md) | geometry fields, maps, GeoServer layers | `wasichai-spring-boot-starter-gis` | `@wasichai/gis` |
| [agent](agent.md) | the AI assistant | `wasichai-spring-boot-starter-agent` | `@wasichai/agent` |
| [testing](testing.md) | test fixtures for your app | `wasichai-test` | `@wasichai/testing` |
