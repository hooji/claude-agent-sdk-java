# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Release workflow

This project ships releases by merging to `main`:

- When you wrap up a job that warrants a release, push a PR from your assigned working
  branch to `main`.
- The user merges that PR promptly, and the merge triggers the release build.
- On your **next turn, assume the previous PR has already been merged.** Keep committing
  to your assigned branch; the new commits accrue to a **new** PR that you open when the
  next chunk of work is ready to push.
- Do not assume a prior PR is still open or try to add to it — once merged it is closed.
  After a merge, `main` has advanced, so a fresh PR from the same branch naturally shows
  only the commits made since then. Create a new PR for each subsequent batch of work.
