# Helm Global Variables

An IntelliJ IDEA 2025.2 (Community Edition) plugin for teams that keep environment-specific
parameters in one shared *meta values* file and reference them from per-team chart values with Helm
template syntax:

```yaml
# values-dev.yaml
image:
  registry: {{ .Values.global.registry }}
ingress:
  host: app.{{ .Values.global.baseDomain }}
replicas: {{ .Values.global.replicaCount | default 2 }}
```

The plugin reads the meta file, and then completes, validates and displays those expressions.

## Features

| | |
|---|---|
| **Completion** | Typing `{{ .Values.global.` offers the keys of the meta file. Mappings are marked with an object icon and a `{n}` child count, and selecting one inserts the dot and re-opens completion so you can walk the tree. Scalars show `= value` in the tail. |
| **Inspection** | `Unknown Helm global variable` (WARNING) underlines the first segment that does not exist in the meta file. Two weak warnings complement it: using a mapping where a scalar is expected, and a variable that exists in some meta files but not all of them. |
| **Quick fix** | *Add `global.x.y` to `.helm-globals.yaml`* creates the key — including any missing parent mappings — in the meta file and navigates to it. |
| **Inline hints** | The resolved value is shown after the expression: `registry: {{ .Values.global.registry }}` `= registry.dev.corp`. Toggle under Settings \| Editor \| Inlay Hints \| Values, or in the plugin's own settings page. |
| **Navigation** | Ctrl+Click / Go to Declaration on any segment jumps to the corresponding key in the meta file. Each segment is its own reference, so `global.image` in `global.image.pullPolicy` navigates to the `image` mapping. |
| **Documentation** | Ctrl+Q (Quick Documentation) on a reference shows the value from every configured meta file, and lists the files where the variable is missing. |

## The meta values file

The meta file is a plain values YAML — its keys *are* the variables:

```yaml
# .helm-globals.yaml
global:
  registry: registry.dev.corp
  baseDomain: dev.corp.io
  replicaCount: 2
  image:
    pullPolicy: IfNotPresent
    pullSecret: regcred
```

`{{ .Values.global.image.pullPolicy }}` resolves to `IfNotPresent`.

**Discovery by convention.** With nothing configured, the plugin looks in the project root and in
every content root for, in order: `.helm-globals.yaml`, `.helm-globals.yml`, `helm-globals.yaml`,
`helm-globals.yml`.

**Multiple meta files.** You can configure several (e.g. one per environment). Completion shows the
union of their keys; documentation shows each file's value side by side; a variable defined in only
some of them gets a weak warning. This is the intended way to spot a variable you added to `dev` but
forgot in `prod`.

## Settings

**Settings | Tools | Helm Global Variables** (project level, stored in `.idea/helm-globals.xml`, so
it can be committed and shared with the team):

- **Meta values files** — one path per line, project-relative or absolute. Empty = use the
  convention above. The resolved files are echoed below the field so you can confirm the paths.
- **Templated values files** — glob patterns selecting which files are analysed. Empty = the
  defaults `values*.y*ml` and `*values*.y*ml`, anywhere in the project. Patterns are matched against
  both the project-relative path and the bare file name.
- **Variable root under `.Values`** — defaults to `global`. Only expressions below this path are
  completed and validated, so ordinary chart values like `{{ .Values.service.port }}` are left alone.
- **Show resolved values as inline hints**.

A meta file is never treated as a templated values file itself, and everything is inert when the
master checkbox is off.

## Supported syntax

- `{{ .Values.global.x }}` and `{{ $.Values.global.x }}`
- quoted, unquoted and embedded: `host: app.{{ .Values.global.baseDomain }}-suffix`
- several expressions in one scalar
- pipelines: `{{ .Values.global.replicaCount | default 2 }}`
- structure-consuming functions — `toYaml`, `range`, `with`, `if`, `index`, `merge`, `dig`,
  `include`, `tpl`, … — suppress the "object used as a scalar" warning, so
  `{{ toYaml .Values.global.image | nindent 2 }}` is accepted. A variable that merely *contains* a
  function name (`global.range`, `global.ifEnabled`) is not mistaken for one.

## Known limitation

Analysis runs on the YAML PSI, so it only sees expressions the YAML parser can recover as a scalar.
Constructs that break YAML parsing are not analysed:

- a bare control line at mapping level, e.g. `{{- if .Values.global.enabled }}` on its own line;
- templates inside YAML *keys*, e.g. `{{ .Values.global.name }}: value`.

These are silently ignored — never falsely reported.

## Building

Requires JDK 21. From the project root:

```bash
./gradlew test          # 34 tests
./gradlew buildPlugin   # -> build/distributions/helm-global-vars-0.1.0.zip
./gradlew verifyPlugin  # JetBrains plugin verifier
./gradlew runIde        # sandbox IDE with the plugin installed
```

Install the zip through **Settings | Plugins | ⚙ | Install Plugin from Disk…**.

## Releasing

Every push to `main` and every pull request runs
[`.github/workflows/build.yml`](.github/workflows/build.yml): tests, `verifyPlugin`, and the plugin
zip uploaded as a build artifact.

To cut a release, push a tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

[`.github/workflows/release.yml`](.github/workflows/release.yml) then builds and verifies at that
version and creates a GitHub Release with the zip attached and generated notes. The tag name minus
the leading `v` becomes the plugin version — `-PpluginVersion` overrides the `pluginVersion` default
in `gradle.properties`, so the version in `plugin.xml` and the zip file name always match the tag.
The same workflow can be started by hand from the Actions tab, passing the version as an input; it
creates the tag for you in that case.

Publishing to the JetBrains Marketplace is not wired up — it needs a marketplace account and a
`publishPlugin` token, so releases go to GitHub only.
