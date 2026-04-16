# Airicraft

Airicraft is a Fabric mod that exposes an in-game agent bridge and a CLI for automating common tasks. It targets Minecraft `1.21.11` with Java `21`, plus a `wrapper/` CLI subproject.

## Prerequisites

- `proto` (for Gradle)
- Java `21`
- A Java version manager (`jenv` on macOS in this guide)

This repository configures Gradle through `.prototools`:

```toml
[plugins.tools]
gradle = "https://raw.githubusercontent.com/eplightning/openjdk-adoptium-proto-plugin/main/build-plugins/gradle.toml"
```

<details>
<summary>macOS setup</summary>

0. Open Terminal
1. Install Gradle via `proto`

   ```shell
   proto install gradle
   ```

2. Install Java 21 via Homebrew

   ```shell
   brew install openjdk@21
   ```

   ```text
   > brew install openjdk@21
   ==> Summary
     /opt/homebrew/Cellar/openjdk@21/21.0.10: 600 files, 347.2MB
   ```

3. Install `jenv` via Homebrew

   ```shell
   brew install jenv
   ```

4. Add `jenv` init to `~/.zshrc`

   ```shell
   echo 'export PATH="$HOME/.jenv/bin:$PATH"' >> ~/.zshrc
   echo 'eval "$(jenv init -)"' >> ~/.zshrc
   exec zsh
   ```

5. Enable `jenv` export plugin

   ```shell
   jenv enable-plugin export
   exec zsh
   ```

6. Register Homebrew JDK and set global version

   ```shell
   jenv add /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   jenv versions
   jenv global openjdk64-21.0.10
   ```

   ```text
   > jenv add /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   openjdk64-21.0.10 added
   21.0.10 added
   21.0 added
   21 added
   ```

7. Verify Java and jenv

   ```shell
   java --version
   jenv versions
   jenv version
   jenv doctor
   ```

   ```text
   > java --version
   openjdk 21.0.10 2026-01-20
   OpenJDK Runtime Environment Homebrew (build 21.0.10)
   OpenJDK 64-Bit Server VM Homebrew (build 21.0.10, mixed mode, sharing)
   ```

</details>

<details>
<summary>Windows setup</summary>

TODO: document Windows setup.

Suggested direction:

- Install Java 21
- Install and configure a Java version manager
- Install `proto` and run `proto install gradle`

</details>

<details>
<summary>Linux setup</summary>

TODO: document Linux setup.

Suggested direction:

- Install Java 21
- Install and configure a Java version manager
- Install `proto` and run `proto install gradle`

</details>

## Verify project build

Run these after Java is configured:

```shell
./gradlew --version
./gradlew build
```

For Minecraft dev client:

```shell
./gradlew runClient
```

### Live JVM debugging with Arthas

Airicraft includes dev-only Gradle helpers for attaching the Arthas CLI to the running Minecraft dev client. Arthas is external tooling: it does not add a mod dependency and does not replace the Airicraft bridge or JDWP.

Start the client:

```shell
source .envrc && ./gradlew runClient
```

For a cold dev client, use the helper to start the client, wait for the bridge, join the first saved world, open LAN, and attach Arthas. This command is cold-only and fails fast if a client is already running:

```shell
scripts/arthas kickstart
```

Attach Arthas manually when the client is already running:

```shell
source .envrc && ./gradlew arthasAttach
```

Use the low-noise HTTP helper for probes after Arthas is attached. It auto-selects the Minecraft Arthas HTTP port when possible and prints compact results:

```shell
scripts/arthas --help
scripts/arthas v
scripts/arthas sc 'ai.moeru.airicraft.*'
scripts/arthas sm ai.moeru.airicraft.ModBridgeServer createStatusResponse
scripts/arthas w ai.moeru.airicraft.ModBridgeServer createStatusResponse
scripts/arthas raw 'thread -n 1'
```

If another JVM already owns the default Arthas port, pass the Minecraft port explicitly:

```shell
scripts/arthas --port 8564 sc ai.moeru.airicraft.ModBridgeServer
```

If process-name selection misses the dev client, find the JVM and attach by PID:

```shell
jps -lv
source .envrc && ./gradlew arthasAttach -Pairicraft.arthas.pid=<pid>
```

Useful Airicraft inspection commands include `sc`, `sm`, `jad`, `watch`, `trace`, `stack`, `tt`, `thread`, `dashboard`, and `ognl`.

Arthas starts with full command power by default. Mutation commands such as `ognl`, `vmtool`, `sysprop`, `vmoption`, `redefine`, `retransform`, and `mc` can alter the live JVM; use them deliberately. To restrict commands for a session, pass a comma-separated list:

```shell
source .envrc && ./gradlew arthasShell -Pairicraft.arthas.disabledCommands=stop,dump,heapdump,redefine,retransform,mc
```

```text
> ./gradlew runClient
The operation couldn't be completed. Unable to locate a Java Runtime.
Please visit http://www.java.com for information on installing Java.
```

## Weave / OpenTelemetry Observability Setup

Airicraft now supports optional LLM-call tracing via OpenTelemetry.
The default mode is vendor-neutral OTLP; if you want W&B Weave, you only need to switch a single profile in config.

### 1) Why this setup exists

- The `planner` and `vision` outbound requests emit spans with:
  - request metadata (provider/model/endpoint)
  - response metadata (status/usage tokens)
  - sanitized request/response summaries
  - failures and error typing
- `generic` profile exports only standard OTEL fields.
- `weave` profile adds a small set of Weave-friendly attributes.

### 2) Edit config

Airicraft writes/reads:

`config/airicraft/agent.yml`

The file is based on `src/client/resources/config/airicraft/agent.yml.example`.

A practical OTLP setup is already scaffolded in that template under `observability`.

### 3) Generic OTLP (default)

Set:

```yaml
observability:
  enabled: true
  exporter: "otlp_http"
  otlpEndpoint: "http://127.0.0.1:4318/v1/traces"
  otlpHeaders: {}
  vendorProfile: "generic"
  captureInputs: false
  captureOutputs: false
  captureImages: false
```

### 4) W&B Weave profile

Use this to send the same spans to Weave with minimal changes:

```yaml
observability:
  enabled: true
  exporter: "otlp_http"
  otlpEndpoint: "https://trace.wandb.ai/otel/v1/traces"
  otlpHeaders:
    wandb-api-key: "<W&B_API_KEY>"
  resourceAttributes:
    wandb.entity: "shinohara-rin"
    wandb.project: "airicraft"
  vendorProfile: "weave"
  captureInputs: false
  captureOutputs: false
  captureImages: false
```

For your project slug `shinohara-rin/airicraft`, set:

- `wandb.entity: "shinohara-rin"`
- `wandb.project: "airicraft"`

### 5) Optional data capture controls

- `captureInputs`: include sanitized input summaries.
- `captureOutputs`: include sanitized output summaries.
- `captureImages`: allow screenshot/tool-capture spans to export image payloads for media-capable backends like Weave. Regular LLM request traces still redact image bytes.

If all are false, only non-content structural tracing metadata is sent.

### 6) Reload workflow

1. Start Minecraft or keep existing session.
2. Edit `config/airicraft/airicraft.yml` and/or `config/airicraft/agent.yml`.
3. Run `airicraft reload` from the wrapper CLI, or `/airicraft reload` in-game.
4. The runtime reloads config live without restarting Minecraft; active agent state is reset during reload.
5. Confirm traces appear in your OTLP collector/Weave dashboard.

## Troubleshooting

### `./gradlew runClient` says `Unable to locate a Java Runtime`

Check:

```shell
java --version
which java
jenv version
jenv doctor
```

Fix:

1. Ensure `~/.zshrc` contains:
   - `export PATH="$HOME/.jenv/bin:$PATH"`
   - `eval "$(jenv init -)"`
2. Enable plugin and reload shell:
   - `jenv enable-plugin export`
   - `exec zsh`
3. Re-add JDK:
   - `jenv add /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
4. Re-select Java version:
   - `jenv global openjdk64-21.0.10`

### `jenv versions` only shows `system`

Cause: JDK not added into jenv, or shell init not loaded.

```shell
jenv add /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
exec zsh
jenv versions
```

### `JAVA_HOME` is empty

```shell
jenv enable-plugin export
exec zsh
env | grep JAVA_
```

If still empty, recheck `~/.zshrc` and run `jenv doctor`.

### `jenv` command not found

```shell
brew list jenv
cat ~/.zshrc | rg 'jenv'
exec zsh
which jenv
```

## Notes

- If `observability.enabled` is false, no OTLP network calls are made.
- If `otlpEndpoint` is empty or unsupported, observability is disabled at runtime with a warning.
- This integration is wired for outbound planner and vision API calls only.
