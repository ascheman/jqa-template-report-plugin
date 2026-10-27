# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

Use the Maven wrapper. Java 11+ is required for source/target; CI builds on JDK 21.

```bash
./mvnw verify              # full build + tests + package
./mvnw compile             # compile only
./mvnw package -DskipTests # build the plugin JAR without running tests
./mvnw clean               # wipe target/
```

Tests live under `src/test/java/...`; Surefire runs them during `verify` and the CI workflow (`.github/workflows/build.yml`) reads `target/surefire-reports/*.xml`.

- `TemplateNamingTest` — the two static naming helpers. Note the asymmetry they encode: a rule id becomes a template name with `-`, but an output file name with `_` (the latter via jQAssistant's `ReportHelper.escapeRuleId`). Do not "harmonise" the separators.
- `TemplateReportPluginTest` — renders through real FreeMarker onto a real temporary filesystem, using `StubReportContext` and a template directory handed in via `template.template-dir`, so the tests never depend on the build's working directory.

`settings.xml` in the repo root is a **symlink** to `../jqa-eclipse-osgi-plugin/settings.xml` (sibling repo). Don't commit a replacement; if the link is broken, restore the symlink rather than copying.

## Architecture

This is a single-class jQAssistant `ReportPlugin` (`net.aschemann.jqassistant.plugins.template.report.TemplateReportPlugin`) registered under the report id `template` via `src/main/resources/META-INF/jqassistant-plugin.xml`. It is loaded into a running `jqassistant analyze` invocation as a plain Maven artifact (see README for the `.jqassistant.yml` snippet).

### Lifecycle

jQAssistant calls `configure(...)` once with merged plugin properties (from `.jqassistant.yml` under `jqassistant.analyze.report.properties.*`), then calls `setResult(Result)` once per rule that declares `<report type="template" .../>`. The plugin:

1. Resolves per-rule overrides for output/template suffixes and label (per-rule `<report><properties>` beats global; for output-format props an empty per-rule value falls through to global, for filter/stub-source an explicit empty disables the feature).
2. Looks up the FreeMarker template `<ruleId-with-colons-to-hyphens><template-suffix>` via a `MultiTemplateLoader`: filesystem layer first (`template.template-dir`, default `jqassistant/templates`, resolved against the JVM working directory; skipped silently if absent), then classpath `/templates` on the **thread context classloader** (which under jQA's plugin loader sees every loaded plugin's resources — templates can ship in a sibling concept plugin's JAR). Filesystem wins on collision. Set `template.template-dir` to an explicit empty string to disable the filesystem layer.
3. If absent on both layers: debug log + return (silent skip — intentional, so a rule set can declare `template` as one of several report types without forcing every rule to ship a template).
4. Renders in one of two modes based on the rule's `<report primaryColumn="...">`:
   - **single-file** (no `primaryColumn`): `<reportDir>/<output-subdir>/<ruleId>.<output-suffix>`, one file, all rows.
   - **per-group** (`primaryColumn` set): groups rows by that column's value, writes `<reportDir>/<output-subdir>/<keyValue>/<ruleId>.<output-suffix>` per group. Keys are sanitized (`[^A-Za-z0-9._-]` → `_`).

### Cross-rule state (the non-obvious part)

`seenKeysByRule` (a `ConcurrentHashMap<String, Set<String>>`) is the **only** way rules communicate across the boundary. In per-group mode the plugin records the filtered group keys it just rendered under the rule's id. Later rules can declare `template.stub-source: <other-rule-id>` to render an empty stub (`rows = []`, `key = <missing>`) for every key in that other rule's recorded set that the current rule did not produce. This exists so consumer-side AsciiDoc `include::` directives can be uniform across a "universe" of keys — every key has every section's fragment, even if some are stubs.

Consequences worth keeping in mind when changing this code:

- Order matters. The universe-source rule **must** run before any rule that uses it as stub source. Enforce with `<requiresConcept refId="..."/>` in the rule XML — the plugin only logs a WARN and skips stubs if the source hasn't been processed yet.
- The filter (`template.primary-key-pattern`) is applied **before** keys enter `seenKeysByRule`. The universe is the filtered key set, not the raw graph state.
- A rule using itself as stub source is explicitly skipped (no-op).
- Single-file mode does **not** populate `seenKeysByRule` — only per-group mode does.

### Data model exposed to templates

```
rule    -> { id, description, severity }
columns -> List<String>
rows    -> List<Map<String,String>> (column-name → label-string from Row.getColumns().getLabel())
key     -> String, per-group mode only
```

Templates should branch on `rows?size == 0` to render a meaningful empty-state line; otherwise stub mode renders a section heading followed by a header-only empty table.

### Config ordinal trap

Properties in `.jqassistant.yml` load at SmallRye Config ordinal 100; Maven `-D` system properties at ordinal 90. Anything set in YAML therefore **cannot** be overridden via CLI `-D`. To change a value temporarily, edit the YAML or pass it via the jQAssistant Maven plugin's `<yaml>` parameter. Don't add code that assumes `-D` works as an override.

## Reference Docs

The user-facing reference lives in two places that should stay aligned with the code:

- `README.adoc` — installation + usage overview.
- `src/main/asciidoc/template-report.adoc` — reference doc suitable for inclusion in a larger jQAssistant manual.
- Class-level Javadoc on `TemplateReportPlugin` — same content, code-adjacent.

When changing semantics (property names, defaults, lookup rules, the stub mechanism), update all three. The Javadoc is the authoritative source for behavior; the `.adoc` files mirror it for readers.

## Release / Distribution

Releases go to Maven Central through the Central Portal, driven locally by JReleaser so that signing uses the local GnuPG agent:

```bash
./mvnw -Prelease clean deploy            # build, sign, stage into target/staging-deploy
./mvnw -Prelease jreleaser:full-release  # upload to Central, tag, create the GitHub release
```

The first command only writes to a local directory — `distributionManagement` in the `release` profile points at `file://target/staging-deploy`. JReleaser (configured in `jreleaser.yml`) picks it up from there, so a failed upload never requires a rebuild. Credentials come from `~/.jreleaser/config.yml` or the environment (`JRELEASER_GITHUB_TOKEN`, `JRELEASER_MAVENCENTRAL_USERNAME`, `JRELEASER_MAVENCENTRAL_PASSWORD`).

OSSRH (`oss.sonatype.org`) was sunset in June 2025 and is gone; the POM no longer references it.

SNAPSHOTs go to GitHub Packages via the `github` profile, which auto-activates when `GITHUB_TOKEN` is set in the environment, under `${env.GITHUB_LOGIN}/jqa-template-report-plugin`. Credentials for that come from a `server` entry with id `github`; `~/.m2/settings-gh.xml` holds one that reads `${env.GITHUB_LOGIN}`/`${env.GITHUB_TOKEN}`, so `./mvnw -s ~/.m2/settings-gh.xml deploy` works without storing a token in a file.

Don't trigger deploys without explicit user confirmation.
