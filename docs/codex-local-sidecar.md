# Codex Local Sidecar

This document describes the local Codex sidecar endpoint for Airicraft planner calls. The repository includes a minimal HTTP-only sidecar in `tools/codex-openai-sidecar/` and a launcher at `scripts/codex-sidecar`.

## Mental Model

Treat the Codex local sidecar as an ordinary OpenAI-compatible endpoint. In Airicraft it sits at the same provider layer as any other planner provider: `providerBaseUrl`, `apiKey`, and `model` in `agent.yml` select it.

The supported HTTP surface is intentionally small:

- `GET /healthz`
- `POST /v1/chat/completions`

The sidecar process keeps a Codex runtime warm, then creates one fresh Codex thread for each HTTP request. It does not persist thread ids, does not expose thread ids, and does not continue a previous thread on the next request. Each planner request must be self-contained so an older Minecraft planning turn cannot contaminate a later one.

Failures are explicit. If the sidecar is down, rejects auth, times out, or returns a provider error, the Airicraft planner request fails. Airicraft does not fall back to another LLM provider and does not roll back `agent.yml` automatically. Restore the previous config manually if you need to leave the sidecar path.

## Sample Files

- `samples/codex/airicraft-planner.config.toml`: Codex config sample for the sidecar runtime.
- `samples/codex/codex-sidecar.env.example`: local environment template for the sidecar process.
- `samples/codex/agent.codex-sidecar.yml`: Airicraft planner config that points at `http://127.0.0.1:3217/v1`.

The sample Codex config uses a dedicated `CODEX_HOME`, `approval_policy = "never"`, `sandbox_mode = "read-only"`, `web_search = "disabled"`, and `[features].shell_tool = false`.

## Start The Sidecar

Create isolated local state and materialize the sidecar Codex config:

```shell
mkdir -p /tmp/airicraft-codex-sidecar-home
mkdir -p /tmp/airicraft-codex-sidecar-workdir
cp samples/codex/airicraft-planner.config.toml \
  /tmp/airicraft-codex-sidecar-home/config.toml
```

Prepare a private env file:

```shell
cp samples/codex/codex-sidecar.env.example .airicraft-codex-sidecar.env
```

Edit `.airicraft-codex-sidecar.env` if you need a different port or bearer token, then authenticate the isolated Codex home once:

```shell
set -a
source .airicraft-codex-sidecar.env
set +a
CODEX_HOME="$CODEX_SIDECAR_CODEX_HOME" codex login
```

For headless machines, use the Codex login method that fits your environment, such as device auth:

```shell
CODEX_HOME="$CODEX_SIDECAR_CODEX_HOME" codex login --device-auth
```

Start the sidecar:

```shell
CODEX_SIDECAR_ENV_FILE=.airicraft-codex-sidecar.env scripts/codex-sidecar
```

The sidecar should bind to `127.0.0.1` for local development. Startup refuses non-loopback hosts unless `CODEX_SIDECAR_ALLOW_NON_LOOPBACK=true` is set. Do not expose this endpoint to the network.

## Configure Airicraft

Copy the sample Airicraft agent config into the active Minecraft config directory:

```shell
cp samples/codex/agent.codex-sidecar.yml run/config/airicraft/agent.yml
```

If the client is running, reload config without restarting Minecraft:

```shell
wrapper/build/install/airicraft/bin/airicraft reload
```

If the wrapper distribution does not exist yet, build it first:

```shell
source .envrc
./gradlew build
```

The key Airicraft planner fields are:

```yaml
providerBaseUrl: "http://127.0.0.1:3217/v1"
apiKey: "airicraft-local-sidecar-token"
model: "codex-local-sidecar"
```

The model string is the local sidecar model name exposed to Airicraft. The Codex model used inside the sidecar is selected by the config copied from `samples/codex/airicraft-planner.config.toml`.

## Isolation Boundary

Use the sample as a separate local runtime, not as your normal interactive Codex home:

- `CODEX_SIDECAR_CODEX_HOME` points at `/tmp/airicraft-codex-sidecar-home` in the sample and is passed to Codex as `CODEX_HOME`.
- The launcher reads the materialized Codex config at `$CODEX_HOME/config.toml`.
- `history.persistence = "none"` avoids retaining local sidecar transcripts.
- `project_doc_max_bytes = 0` keeps repo guidance out of sidecar planner threads.
- `sandbox_mode = "read-only"` prevents filesystem writes from Codex tool execution.
- `approval_policy = "never"` makes blocked actions fail closed instead of prompting.
- `web_search = "disabled"` removes web search from this runtime.
- `[features].shell_tool = false` removes the default shell tool.

The bearer token is only a local shared secret between Airicraft and the sidecar. Keep it local, rotate it if it appears in logs, and keep `agent.yml` out of public examples if you change it to a real secret.

## Failure Semantics

The sidecar path is not a backup provider. It is the selected provider.

- Bad or missing bearer token: the sidecar rejects the HTTP request, and the planner request fails.
- Missing `CODEX_SIDECAR_BEARER`, missing `CODEX_SIDECAR_CODEX_HOME`, missing `$CODEX_HOME/config.toml`, or accidentally pointing at default `~/.codex`: the sidecar refuses to start.
- Sidecar not listening: Airicraft reports a provider or transport failure for planner calls.
- Codex config cannot satisfy the requested isolation policy: treat the sidecar as unhealthy and fix the config before retrying.
- Request timeout: Airicraft fails that planner call. Increase `requestTimeoutMillis` only if the sidecar normally needs more time.
- Invalid response shape: Airicraft treats the planner response as failed rather than retrying through another provider.

## Troubleshooting

- `curl http://127.0.0.1:3217/healthz` fails: confirm the sidecar process is running and bound to `127.0.0.1:3217`.
- Planner requests return auth errors: make `CODEX_SIDECAR_BEARER` match `apiKey` in `agent.yml`.
- Planner requests hit the wrong path: keep `/v1` in `providerBaseUrl`; Airicraft will call `/v1/chat/completions`.
- Airicraft still uses the old provider: copy the sample to the active game directory and run `airicraft reload`, or restart the dev client.
- Sidecar appears to use normal Codex state: set `CODEX_SIDECAR_CODEX_HOME` before launching the sidecar and confirm `config.toml` is under that directory.
- Sidecar cannot call the internal Codex model: authenticate the isolated `CODEX_SIDECAR_CODEX_HOME` with `codex login` and confirm the selected model is available.
- Requests include stale context: verify the sidecar creates a new Codex thread per HTTP request and does not continue an earlier thread.

## Verification Commands

Run sidecar unit tests:

```shell
cd tools/codex-openai-sidecar
node --test
```

Check the sidecar health endpoint:

```shell
curl -fsS http://127.0.0.1:3217/healthz
```

Check the Chat Completions endpoint:

```shell
set -a
source .airicraft-codex-sidecar.env
set +a
curl -fsS -X POST http://127.0.0.1:3217/v1/chat/completions \
  -H "Authorization: Bearer $CODEX_SIDECAR_BEARER" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "codex-local-sidecar",
    "messages": [
      {"role": "user", "content": "Return a short ok response."}
    ]
  }'
```

Check the active Airicraft config after reload:

```shell
wrapper/build/install/airicraft/bin/airicraft status
```

For a live planner smoke, start `runClient`, join a world, then trigger a normal planner request through the existing Airicraft UI or wrapper flow. The expected result is a planner response from the local sidecar model, not a fallback response from another provider.
