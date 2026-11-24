# CLAUDE.md

Project instructions for Claude Code. The shared, tool-agnostic instructions live in AGENTS.md and are
imported here, so other AI agents follow the same rules. Edit AGENTS.md, not this file, for anything
that isn't specific to Claude Code.

@AGENTS.md

## Claude Code specifics

- **Skills** (`.claude/skills/`), invoke as slash commands or let Claude pick them up:
  - `/feature-module <name>`: scaffold a feature module (`api`/`application`/`domain`/`infrastructure`)
    that passes the architecture rules.
  - `/adr <title>`: record a decision as the next ADR and update the index.
  - `/learning-note <topic>`: write the next learning note and update the learning index and timeline.
- **Subagent** `architecture-reviewer` (`.claude/agents/`): read-only review of the working-tree diff
  against the ADRs, the architecture rules and the definition of done. Use it before reporting a
  non-trivial change as finished.
- **Permissions** (`.claude/settings.json`): Maven builds and read-only git commands run without a
  prompt; force pushes, tag deletion and reading `.env`/key files are denied. Personal overrides go in
  `.claude/settings.local.json` (git-ignored); personal notes in `CLAUDE.local.md` (git-ignored).
- Run the full suite with `mvn -q test` in the background when doing other work in parallel; it takes
  ~15 s. Filter its output by the `Tests run:` lines; don't read the expected stack traces.
