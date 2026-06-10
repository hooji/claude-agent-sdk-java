# Releasing

This fork has two independent release paths plus CI. All workflows live in
`.github/workflows/`.

## Workflows at a glance

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `ci.yml` | pull requests | Build + tests. **Note:** the integration tests (`*IT`) require a Claude CLI on the runner; without one they fail at discovery by design (see below). |
| `release.yml` ("Tag Release") | push of a `v*` tag | Builds the SDK and the fat jar, creates a **GitHub Release** with the artifacts. |
| `maven-central-release.yml` | manual (`workflow_dispatch` with a version input) | Deploys the thin `claude-code-sdk` jar to **Maven Central** (requires OSSRH/GPG secrets), then creates and pushes the `v<version>` tag itself. |
| `publish-snapshot.yml` | push to `main` | Publishes a `-SNAPSHOT` build (requires GPG signing secrets). |

## Cutting a GitHub release

The tag-triggered release is self-contained — it derives the version **from the tag
name** and runs `versions:set` during the build, so the poms don't strictly need to be
bumped first (bumping them keeps the repo's declared version honest, though):

```bash
git tag -a v1.2.0 <commit-on-main> -m "Release v1.2.0"
git push origin v1.2.0
```

The workflow then:

1. Derives `1.2.0` from `v1.2.0`.
2. Sets the Maven version in the reactor **and** the standalone `fatjar/` module.
3. `./mvnw -pl claude-code-sdk -am -DskipTests install`, then packages `fatjar/`.
   (Tests are skipped because the ITs need a live Claude CLI — see CI note below.)
4. Creates the GitHub Release with two assets:
   - **`claude-code-sdk-all-<v>.jar`** — fat jar: the SDK plus all runtime dependencies
     (Jackson, Reactor, zt-exec) and a NOP SLF4J binding. Drop-on-classpath ready.
   - **`claude-code-sdk-all-<v>-sources.jar`** — the SDK library sources, for IDE
     attachment (dependency sources are not included).

## Version locations

Three poms carry the version: the root `pom.xml`, `claude-code-sdk/pom.xml` (parent
reference), and `fatjar/pom.xml`. The fatjar module is **not** part of the root reactor
(it consumes the SDK as a normal dependency), so a plain `versions:set` at the root
misses it:

```bash
./mvnw versions:set -DnewVersion=1.2.0 -DgenerateBackupPoms=false
(cd fatjar && ../mvnw versions:set -DnewVersion=1.2.0 -DgenerateBackupPoms=false)
```

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

`publish-snapshot.yml` requires GPG signing secrets (`maven-gpg-plugin`); without them
configured on the repository it fails at the sign step.
