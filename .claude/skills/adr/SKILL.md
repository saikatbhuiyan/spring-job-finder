---
name: adr
description: Record a significant technical decision for Job Finder as the next Architecture Decision Record in docs/adr/ and add it to the index. Use when a change introduces or changes an architectural, API-contract, security, persistence or operational decision.
argument-hint: <decision title>
---

# Write an ADR

Decision: `$ARGUMENTS`.

## When an ADR is needed

A choice another engineer would ask "why?" about: architecture, API contract, error format, security,
persistence, a new dependency of consequence, deployment, an architecture rule added or changed.
Not for routine code that follows an existing ADR.

## Steps

1. Read `docs/adr/README.md` and every ADR the decision touches. Never rewrite an accepted ADR: if this
   changes one, the new ADR **supersedes** or **extends** it, and the old one's status line and the
   index row are updated to say so (that is the only edit allowed to an old ADR).
2. Number: next free four-digit number (`ls docs/adr`). File: `docs/adr/NNNN-kebab-case-title.md`.
3. Gather evidence first. This project's ADRs are backed by **measurements** (what was tried, what broke,
   numbers). Run the experiments; don't cite behavior from memory.
4. Write it in the house format (see `docs/adr/0012-rate-limiting.md`):

```markdown
# ADR NNNN: <Title>

- **Status:** Accepted
- **Date:** <YYYY-MM-DD, today>
- **Extends:** / **Supersedes:** [ADR XXXX](XXXX-....md) (<one-line reason>)   ← only if applicable

## Context

The problem, the forces, and the options considered, with measured results (tables welcome).

## Decision

Numbered, concrete decisions: what, where (config keys, classes), defaults, and which tests lock it in
(including mutation checks done).

## Consequences

Trade-offs accepted, what becomes easier/harder, follow-up work and the conditions that would trigger it.
```

5. Add a row to the table in `docs/adr/README.md`: `| [NNNN](NNNN-....md) | <one-line decision> | Accepted |`.
6. Reference the ADR from the code it governs (Javadoc / properties comment: `See docs/adr/NNNN.`), and
   from `AGENTS.md`'s API contract table and `README.md` if it changes a convention listed there.
