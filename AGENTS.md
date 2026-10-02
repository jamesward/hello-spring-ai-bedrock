# hello-spring-ai-bedrock

Minimal code sample (Kotlin, Spring Boot 4, Spring AI 2) that calls Amazon Bedrock through Spring AI's
Bedrock Converse starter (`DemoApplication`). Not published.

Follow the `zen-of-projects` Skill (extract it with `./gradlew extractSkillsJars`); this file records
only project-specific facts and exceptions.

## Skills

`zen-of-projects`, `zen-of-james` (from `com.jamesward:skills`, extracted to `.kiro/skills/`).

## MCP

`javadocs` (https://www.javadocs.dev/mcp), configured in `.mcp.json` / `.kiro/settings/mcp.json`. Use
its `get_latest_version` for version lookups and its source/doc tools for API questions.

## Build & test

- `./gradlew build` (what CI runs, Java 21).
- `./gradlew bootRun` runs the app. It calls Bedrock and needs `AWS_BEARER_TOKEN_BEDROCK`, so it's
  paid and manual only. Never run it in the maintenance routine.

## Branches

`main` is the default branch and the only one the maintenance routine maintains. Feature branches (for
example `towl-advisor`) are experiments: don't touch them.
