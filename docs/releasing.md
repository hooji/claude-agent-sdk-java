# Releasing

Releases are **automatic**: merging a pull request into `main` cuts a new
GitHub Release. All workflows live in `.github/workflows/`.

> This fork publishes **GitHub Releases only** — it does **not** publish to
> Maven Central. The legacy `maven-central-release.yml` / `publish-snapshot.yml`
> workflows and the Maven `release` profile (central-publishing + GPG signing)
> were removed.

## Workflows at a glance

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `ci.yml` | pull requests | Build + tests. **Note:** the integration tests (`*IT`) require a Claude CLI on the runner; without one they fail at discovery by design (see below). |
| `version-bump.yml` ("Bump version and release") | a PR is **merged** into `main` (or manual dispatch) | Computes the next version from the latest `v*` tag, creates and pushes that tag, then dispatches **Tag Release**. |
| `release.yml` ("Tag Release") | push of a `v*` tag, or dispatch with a `tag` input | Builds the SDK and the fat jar, creates a **GitHub Release** with the artifacts. |

## The automatic flow

```
merge PR into main
  └─ version-bump.yml: latest tag v1.2.3 → next v1.2.4 → push tag
       └─ dispatch release.yml with tag=v1.2.4
            └─ GitHub Release v1.2.4 (fat jar + sources jar)
```

- **Default bump is patch.** Add a `release:minor` or `release:major` label to
  the PR to bump that part instead.
- **Opt out** of releasing for a given merge with a `release:skip` label or
  `[skip release]` in the PR title.
- version-bump **dispatches** Tag Release rather than relying on the tag-push
  event, because a tag pushed with the automatic `GITHUB_TOKEN` does not start
  another workflow (so no Personal Access Token is required).

## Cutting a release by hand

Either path is equivalent to an automatic merge release:

- **Bump from the Actions tab:** run **Bump version and release**
  (`workflow_dispatch`) — pick `patch` / `minor` / `major`, or type an explicit
  `version` (e.g. `1.3.0`).
- **Build an existing tag:** run **Tag Release** (`workflow_dispatch`) with a
  `tag` input (e.g. `v1.3.0`), or just push a `v*` tag yourself:

```bash
git tag -a v1.2.0 <commit-on-main> -m "Release v1.2.0"
git push origin v1.2.0
```

Tag Release then:

1. Derives `1.2.0` from `v1.2.0`.
2. Builds with `-Drevision=1.2.0` (no POM mutation — see *Versioning* below),
   installing `claude-code-sdk` and packaging `fatjar/`. (Tests are skipped
   because the ITs need a live Claude CLI — see the CI note below.)
3. Creates the GitHub Release with two assets:
   - **`claude-code-sdk-all-<v>.jar`** — fat jar: the SDK plus all runtime
     dependencies (Jackson, Reactor, zt-exec) and a NOP SLF4J binding.
     Drop-on-classpath ready.
   - **`claude-code-sdk-all-<v>-sources.jar`** — the SDK library sources, for IDE
     attachment (dependency sources are not included).

## Versioning (`${revision}`)

The build uses Maven's CI-friendly `${revision}` property, so **no pom carries a
hard-coded release number**. The release version comes entirely from the tag,
passed as `-Drevision=<version>`; `flatten-maven-plugin` resolves `${revision}`
in the installed poms so consumers never see the placeholder.

The dev-build baseline lives in a `<revision>` property in each standalone build:

- root `pom.xml` — inherited by `claude-code-sdk`, whose `<parent>` reference
  uses `${revision}`.
- `fatjar/pom.xml` — not part of the root reactor (it consumes the SDK as a
  normal dependency), so it carries its own `<revision>`.

To change the local dev baseline, edit those `<revision>` values; releases
ignore them (the tag wins via `-Drevision`).

## CI and the integration tests

The integration tests come in two tiers, both rooted at `ClaudeCliTestBase`:

- **CLI binary required.** Discovery still **fails loudly** (rather than skipping) when
  no Claude CLI is found — `ci.yml` installs it
  (`npm install -g @anthropic-ai/claude-code`), so a discovery failure means the
  workflow is broken, not that tests should be quietly skipped.
- **Live model required.** Most ITs hold real conversations. They run only when
  credentials are detected — `ANTHROPIC_API_KEY` / `CLAUDE_CODE_OAUTH_TOKEN` in the
  environment, a CLI login (`~/.claude/.credentials.json`), or the explicit
  `CLAUDE_SDK_LIVE_TESTS=true` opt-in (for environments with host-managed auth that is
  invisible to the process). Otherwise they **skip** with a clear message. Binary-only
  tests (e.g. `CLIFlagParityIT`, which inspects `claude --help`) opt out via
  `requiresApi()` and always run.

`ci.yml` forwards the `ANTHROPIC_API_KEY` repository secret to the test step, so adding
that secret is all it takes to light up the full live suite in CI (note: secrets are not
exposed to pull requests from forks). Without it, CI runs the unit tests plus the
binary-level ITs — and stays green.
