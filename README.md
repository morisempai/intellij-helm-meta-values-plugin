# Helm Global Variables

An IntelliJ IDEA 2025.1–2025.2 (Community Edition) plugin for teams that keep environment-specific
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
| **Inspection** | `Unknown Helm global variable` (WARNING) underlines the first segment that does not exist in the meta file, plus a weak warning for using a mapping where a scalar is expected. Two more inspections cover a meta file that cannot be found and — opt-in — a variable missing from some of several meta files. |
| **Quick fix** | *Add `global.x.y` to `.helm-globals.yaml`* creates the key — including any missing parent mappings — in the meta file and navigates to it. |
| **Inline hints** | The value the expression renders is shown after it: `registry: {{ .Values.global.registry }}` `= registry.dev.corp`. A `range` over a list is previewed filled in, over as many lines as it renders. Toggle under Settings \| Editor \| Inlay Hints \| Values, or in the plugin's own settings page. |
| **Navigation** | Ctrl+Click / Go to Declaration on any segment jumps to the corresponding key in the meta file. Each segment is its own reference, so `global.image` in `global.image.pullPolicy` navigates to the `image` mapping. |
| **Documentation** | Ctrl+Q (Quick Documentation) on a reference shows the comment documenting the variable, its value in every configured meta file, and the files where it is missing. On a function name it shows the signature and what the function does. |
| **Template functions** | Go template built-ins, Sprig functions and the control actions are completed at the start of an expression, after a pipe and inside parentheses, with their arguments and a one-line description. |

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

### Documenting variables

The `#` comment block directly above a key documents it. The block shows up in the completion popup
and in Quick Documentation (Ctrl+Q) on any reference to that variable.

```yaml
global:
  # -- Container registry all images are pulled from.
  # Must be reachable from the cluster nodes.
  registry: registry.dev.corp

  # A plain comment works too, the -- marker is optional.
  baseDomain: dev.corp.io

  replicaCount: 2  # used when there is no block above
```

- The leading `--` is [helm-docs](https://github.com/norwoodj/helm-docs) syntax and is stripped when
  present, so the same meta file can generate a README table. It is optional: any comment block
  counts.
- An empty line between the comment and the key detaches it — that is how you write a note that is
  not documentation.
- helm-docs metadata lines (`# @default -- …`, `# @section -- …`) are recognised and left out of the
  rendered text.
- Mapping keys can be documented too, not just leaves.
- A trailing comment on the key's own line is used when there is no block above it.

**Discovery by convention.** With nothing configured, the plugin looks in the project root and in
every content root for, in order: `.helm-globals.yaml`, `.helm-globals.yml`, `helm-globals.yaml`,
`helm-globals.yml`. An individual file can override this with a directive comment — see
[Pointing a file at its meta file](#pointing-a-file-at-its-meta-file).

**Multiple meta files.** You can configure several. Completion shows the union of their keys, and
documentation shows each file's value side by side. A variable only has to exist in one of them.

### Parallel environment files

Meta files come in two arrangements, and they want opposite behaviour:

- **Complementary** — each file contributes its own keys (`shared.yaml` + `dev.yaml`). Every variable
  is absent from all the other files by design. This is the default assumption: nothing is reported.
- **Parallel environments** — each file describes the *same* variables with different values
  (`dev.yaml` + `prod.yaml`). Here a gap usually means someone added a variable to one environment
  and forgot another.

For the second case, enable **Helm global variable missing in some meta values files** under
Settings | Editor | Inspections | YAML | Helm. It reports any variable that some, but not all, of the
meta files define, and its quick fix adds the variable to each file that lacks it. It is off by
default because it is pure noise in the complementary arrangement.

## Pointing a file at its meta file

A values file can name its own meta file with a directive comment, the way
`# yaml-language-server: $schema=…` attaches a JSON schema:

```yaml
# helm-globals: meta/dev.yaml
registry: {{ .Values.global.registry }}
```

The directive **replaces** whatever the settings configure, for that file only, and it **opts the
file in**: a file carrying a directive is analysed even when it does not match any of the configured
globs, so `overrides.yaml` or `foo.tpl.yaml` work without touching the settings.

Long form, with several meta files and an explicit variable root:

```yaml
# helm-globals: $meta=meta/dev.yaml $meta=meta/shared.yaml $root=global
```

- A bare token is a meta file path; `$meta=` says the same thing explicitly. Separate tokens with
  spaces or commas.
- Several paths are merged, exactly like several configured meta files: completion shows the union,
  and a variable only needs to exist in one of them. See
  [Parallel environment files](#parallel-environment-files) if you want the gaps reported.
- Paths resolve relative to the file's own directory first — as with `# yaml-language-server` — then
  to the project root and the content roots. Absolute paths work too.
- `$root=global` narrows the scope for this file; a bare `$root=` widens it back to every
  `.Values.*` path, overriding the setting.
- A path that does not resolve is reported by the *Unresolved Helm meta values file* inspection,
  rather than silently leaving the file with no variables.

## Settings

**Settings | Tools | Helm Global Variables** (project level, stored in `.idea/helm-globals.xml`, so
it can be committed and shared with the team):

- **Meta values files** — one path per line, project-relative or absolute. Empty = use the
  convention above. The resolved files are echoed below the field so you can confirm the paths.
- **Templated values files** — glob patterns selecting which files are analysed. Empty = the
  defaults `values*.y*ml` and `*values*.y*ml`, anywhere in the project. Patterns are matched against
  both the project-relative path and the bare file name.
- **Variable root under `.Values`** — optional, empty by default: every `{{ .Values.* }}` path in the
  file is completed and validated against the meta file. Set it to narrow the scope — with `global`,
  only `{{ .Values.global.* }}` is analysed and anything else, such as `{{ .Values.service.port }}`,
  is left alone.
- **Show resolved values as inline hints**.
- **Hide YAML errors caused by template expressions** — see [Known limitations](#known-limitations).

A meta file is never treated as a templated values file itself, and everything is inert when the
master checkbox is off.

## Template functions and pipes

Completion offers the Go template built-ins, the Sprig functions Helm bundles and the control
actions, wherever a function can start: at the beginning of an expression, after a `|`, and inside
`(`. Each entry shows its arguments and a one-line description, and Ctrl+Q gives the same on any
function name already in the file.

```yaml
registry: {{ .Values.global.registry | quote }}
host: {{ printf "%s.%s" .Values.global.name .Values.global.baseDomain }}
replicas: {{ .Values.global.replicaCount | default 2 }}
```

Inline hints show what the *whole* expression renders, not just the variables in it:

| Expression | Hint |
|---|---|
| `{{ .Values.global.registry }}` | `= registry.dev.corp` |
| `{{ .Values.global.registry \| quote }}` | `= "registry.dev.corp"` |
| `{{ .Values.global.registry \| upper \| quote }}` | `= "REGISTRY.DEV.CORP"` |
| `{{ print .Values.protocol "://" .Values.url "/v1" \| quote }}` | `= "https://example/v1"` |
| `{{ printf "%s://%s" .Values.protocol .Values.url }}` | `= https://example` |
| `{{ b64enc .Values.protocol .Values.url }}` | `protocol = https, url = example` |

The evaluator understands string literals, numbers, `.Values` paths, parenthesised sub-expressions
and pipes, over these functions: `print`, `printf` (`%s`, `%v`, `%d`, `%q`), `cat`, `quote`,
`squote`, `upper`, `lower`, `title`, `trim`, `trimPrefix`, `trimSuffix`, `replace`, `toString` and
`default`.

Anything outside that — `b64enc` in the last row, `.Release.Name`, a variable missing from the meta
file, or one of your chart's own helpers — makes the whole expression unevaluable, and the hint falls
back to listing the variables it mentions, as in the last row. Partial evaluation is never shown: a
hint is either what the expression really renders or an explicit list of parts.

An unknown function name is never reported as an error: charts define their own helpers with
`define`, so the catalogue is for completion only.

## Range previews

A `range` over a list in the meta file is shown filled in, one line per line it renders, stacked
just above the closing `{{ end }}`:

```yaml
hosts:
{{- range .Values.global.hosts }}
  - host: {{ . | quote }}
    port: {{ .Values.global.port }}
      - host: "a.dev.corp"      ← preview
        port: 8080
      - host: "b.dev.corp"
        port: 8080
{{- end }}
```

The assignment forms bind their variables, so `{{ $host }}` and `{{ $i }}` render too:

```yaml
{{- range $i, $host := .Values.global.hosts }}
  - id: {{ $i }}
    name: {{ $host }}
{{- end }}
```

A list of mappings works through its fields, which is the usual shape for something like `services`:

```yaml
# .helm-globals.yaml
global:
  services:
    - name: api
      port: 8080
    - name: web
      port: 80
```

```yaml
services:
{{- range .Values.global.services }}
  - name: {{ .name }}
    port: {{ .port }}
      - name: api        ← preview
        port: 8080
      - name: web
        port: 80
{{- end }}
```

`{{ $service.name }}` works too, after `range $service := .Values.global.services`, as do nested
fields such as `{{ .probe.path }}`.

A **mapping** is iterated the same way, sorted by key as Go does, with the first variable bound to
the key:

```yaml
# .helm-globals.yaml
global:
  endpoints:
    api:
      port: 8080
    web:
      port: 80
```

```yaml
endpoints:
{{- range $key, $value := .Values.global.endpoints }}
  - name: {{ $key }}
    port: {{ $value.port }}
      - name: api        ← preview
        port: 8080
      - name: web
        port: 80
{{- end }}
```

Because the element comes from the meta file, its fields are known exhaustively, so a guard on one
that is absent is answered rather than giving up: `{{- if $value.probe }}` keeps the entries that
have a probe and drops the rest.

- The list has to hold either all scalars or all mappings, and every expression in the body has to be
  one the evaluator understands — including every field it reaches for. If any line cannot be
  rendered exactly, the whole preview is dropped rather than shown half-filled.
- A nested `range`, or a `with` that rebinds the dot, is more than the preview models and drops it.
- Previews stop after 12 lines, always at an entry boundary, and say how many entries were left out.
  A preview cut in the middle of an entry would read as broken structure rather than as one that
  stopped.
- Lists show as `[n]` in completion, with the item count instead of their flattened text.

## Conditions

An `if` shows whether its branch is taken, given what the meta file says:

```yaml
{{- if .Values.global.ingressEnabled }}      = true
{{- if and .Values.global.ingressEnabled (eq .Values.global.scheme "http") }}      = true
{{- if .Release.IsUpgrade }}
```

Conditions are evaluated with Go's notion of emptiness — `false`, `0`, `""`, `nil` and an empty
list or map are false, everything else is true — over `not`, `empty`, `and`, `or`, `eq`, `ne`, `lt`,
`le`, `gt` and `ge`. Comparisons are numeric when both sides are numbers, and undecided when a
number is compared with a string, which is an error in Helm anyway.

A condition that cannot be decided, such as the `.Release.IsUpgrade` above, shows nothing. That is
deliberately distinct from a condition that is false: the plugin only knows the meta file, not the
release.

Inside a range preview, conditions choose the branches that appear:

```yaml
{{- range .Values.global.hosts }}
  - name: {{ . }}
{{- if .Values.global.ingressEnabled }}
    ingress: yes
{{- else }}
    ingress: no
{{- end }}
      - name: a.dev.corp        ← preview, else branch dropped
        ingress: yes
      - name: b.dev.corp
        ingress: yes
{{- end }}
```

`else` and `else if` chains are followed. If any condition in the body cannot be decided, the preview
is dropped rather than guessing at a branch.

## Supported syntax

- `{{ .Values.global.x }}` and `{{ $.Values.global.x }}`
- quoted, unquoted and embedded: `host: app.{{ .Values.global.baseDomain }}-suffix`
- several expressions in one scalar
- pipelines: `{{ .Values.global.replicaCount | default 2 }}`
- structure-consuming functions — `toYaml`, `range`, `with`, `if`, `index`, `merge`, `dig`,
  `include`, `tpl`, … — suppress the "object used as a scalar" warning, so
  `{{ toYaml .Values.global.image | nindent 2 }}` is accepted. A variable that merely *contains* a
  function name (`global.range`, `global.ifEnabled`) is not mistaken for one.

## Known limitations

**Validation** scans the file's raw text, so it covers every `{{ ... }}` expression, including the
ones the YAML parser cannot represent — a bare control line such as `{{- if .Values.global.enabled }}`,
or a template used as a key, `{{ .Values.global.name }}: value`. Expressions inside `#` comments are
skipped.

**Completion, navigation, quick documentation and inline hints** are anchored to PSI elements, so
they work only where the expression sits inside a value the YAML parser recovers as a scalar. On a
bare control line you get validation but no Ctrl+Click or hint.

A values file containing control-flow lines is not valid YAML, so IDEA's YAML support reports errors
on it — *Invalid child element in a block mapping* for a `{{- range … }}` line, and similar. The
plugin hides those: an error is dropped when it overlaps a `{{ … }}` region **and** the file is one
the plugin analyses. Genuine YAML mistakes elsewhere in the file, and every file the plugin does not
recognise, are left alone. Turn it off with *Hide YAML errors caused by template expressions* in the
settings if you would rather see them.

## Compatibility

`since-build 251` / `until-build 252.*` — IntelliJ IDEA 2025.1 and 2025.2, Community or Ultimate.

The plugin is compiled against 2025.1, the oldest supported IDE, so that no newer API can slip in
unnoticed, and `verifyPlugin` checks the result against both ends of the range (2025.1.3, build
`251.26927.53`, and 2025.2). Only bundled platform and YAML APIs are used; there is no dependency on
the commercial Helm support in Ultimate.

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
