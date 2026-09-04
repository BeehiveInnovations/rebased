---
name: commits
description: >-
  Use this skill whenever the user asks to commit changes, write or fix a
  commit message, amend or rename a commit, or do a workflow that includes
  committing in this fork. Write full commit messages by default, keep
  requested ticket IDs or trailers when supplied, and use the configured
  upstream unless the user requests another destination.
---

# Commits

## Workflow

1. Review the full diff (staged + unstaged) before writing the message.
2. Identify the motivation: why is this change being made?
3. Write a concise subject line. Include a ticket only when the user supplied one.
4. Write a body for any non-trivial change: explain the "why", summarize
   key design decisions, and note any non-obvious behavioral effects.
   Do not just restate what the diff shows — explain what the reader
   cannot see from the code alone.

## Source Of Truth

- Follow the user's current Git request and the `$git-commit-push` workflow.
- Do not require a YouTrack ticket or JetBrains Safe Push for this fork.
- Push normally to the configured upstream unless the user explicitly requests Safe Push or another destination.
- Do not use Conventional Commits unless the user asks for them.

## Quick Rules

- Never invent, search for, or require a ticket ID. Preserve one when the user supplies it.
- When a ticket ID is present, it may lead the subject without a subsystem prefix.
- Clearly non-behavioral changes may use a non-production label such as `tests`, `cleanup`, `refactor`, `docs`, `format`, `style`, `setup`, or `misc`.
- If there is any doubt whether the change is behavioral, do not use a non-production label.
- Write a full commit message (subject + body) for any non-trivial change.
  Subject-only is acceptable only for truly mechanical changes (typo, import, format).
- The body must explain *why* the change was made and summarize key decisions.
  Do not just list what files changed — the diff already shows that.
- Keep the first line concise; put rationale and important behavior notes in the body.
- If the user requests a suffix such as `IJ-MR-100`, put it in a final separate paragraph after a blank line.
- Do not use commits starting with `WIP`, `fixup!`, `squash!`, or `amend!`.

## Examples

```text
git: show outgoing state for each repository

Show cached incoming and outgoing state in the repository rows so users can
choose the correct repository without opening each branch submenu.
```

```text
tests cidr: migrate JUnit 5 coverage

Convert remaining JUnit 4 test suites under cidr/coverage to JUnit 5.
Parametrized tests now use @MethodSource instead of Theories runner.
```

## Anti-patterns

- Subject-only messages for non-trivial changes (even non-production ones).
- Restating the diff ("changed X in file Y") instead of explaining motivation.
- Using Conventional Commits format (`fix(scope): ...`).
