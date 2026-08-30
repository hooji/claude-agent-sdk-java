# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Code standards (public API)

New and fork-added public API must not use these types in signatures:

- **No `Optional`** return values — return the value directly and use null for "not
  present", with the null case documented in the javadoc.
- **No `java.nio.file.Path`** parameters or return values — take and return `String`
  paths at the public boundary (convert with `Path.of(...)` internally), matching the
  rest of the SDK (`workingDirectory(String)`, the transcript toolkit, etc.).
- **No `java.util.stream.Stream`** return values — return `List` or `Iterable` instead.
  (Reactor `Flux`/`Mono` on `ClaudeAsyncClient` are that client's idiom and are fine.)

Some upstream-inherited APIs (e.g. `QueryResult.text()`, `StreamEvent.textDelta()`,
`Query.stream(...)`) predate this standard and still violate it; do not add new
violations, and migrate old ones only as an explicitly requested breaking change.

## Release workflow

This project ships releases by merging to `main`:

- When you wrap up a job that warrants a release, push a PR from your assigned working
  branch to `main`.
- The user merges that PR promptly, and the merge triggers the release build.
- On your **next turn, assume the previous PR has already been merged.** Keep committing
  to your assigned branch; the new commits accrue to a **new** PR that you open when the
  next chunk of work is ready to push.
- Each release also publishes the parent POM and the `claude-code-sdk` jar to this repo's
  **GitHub Packages Maven registry** (`org.springaicommunity:claude-code-sdk`), then **prunes
  the registry to the newest version only** — the GitHub Release page is the permanent
  archive, so never depend on an older registry version. The fat jar remains a Release-page
  asset only.
- Do not assume a prior PR is still open or try to add to it — once merged it is closed.
  After a merge, `main` has advanced, so a fresh PR from the same branch naturally shows
  only the commits made since then. Create a new PR for each subsequent batch of work.
