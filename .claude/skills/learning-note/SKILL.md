---
name: learning-note
description: Write or update a beginner-friendly Job Finder learning note in learning/ (next number, house layout, real verified output) and update the learning index and timeline. Use whenever a change applies a new Java/Spring concept, or a concept is studied, so the owner can learn from it.
argument-hint: <topic>
---

# Write a learning note

Topic: `$ARGUMENTS`.

The project owner is **new to Java and Spring**. Notes are their textbook: explain from first
principles, step by step, and never skip the "why".

## Steps

1. Read `learning/README.md` (index, status legend, timeline) and the closest existing notes. If the
   topic belongs in an existing note, extend that note (new numbered section) instead of a new file.
2. New note: next free number `NN` (`ls learning`), file `learning/NN-kebab-case-topic.md`.
   `learning/roadmap.md` is unnumbered; never renumber existing notes.
3. Run every example and paste **real output** (commands, HTTP responses, SQL, logs, timings). Use a
   scratch directory for throwaway demos; don't leave demo code in `src/`. Never invent output.
4. Layout (every note has these sections, in this order):

```markdown
# NN — <Title>

## Goal
What this step is for, in two or three sentences.

## Concepts            ← may be split into numbered sections (## 1. …, ## 2. …)
Plain-language theory; vocabulary defined before use; small diagrams/tables.

## In this project
The exact code, with relative links to the files, and why each choice was made (link the ADR).

## Run it / verify it
Commands and their real output.

## Common mistakes
Errors seen or likely, the exact message, what it means, the fix.

## Interview questions
Short Q&A for revision.
```

5. Update `learning/README.md`:
   - a row in the index table: `| NN | [Title](NN-file.md) | what it covers | status |`, status one of
     ✅ Applied, 🧪 Verified in a demo / learned, 🔜 Planned;
   - a row in the **Project timeline**: `| <today YYYY-MM-DD> | <what was applied/studied, key findings> | NN |`.
6. Update `learning/roadmap.md` if a roadmap item moved, and the note count / topic list in `README.md`.
7. Commit separately from the code when asked (`Add <topic> note`).
