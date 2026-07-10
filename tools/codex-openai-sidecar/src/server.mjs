#!/usr/bin/env node

import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import {
  HttpError,
  buildCodexPrompt,
  buildOpenAiChatCompletion,
  parseCodexAssistantText,
  validateChatCompletionRequest
} from "./openai-adapter.mjs";
import { readBody } from "./http-body.mjs";
import { CodexMcpRuntime, McpTimeoutError } from "./mcp-client.mjs";

let config;
let runtime;
let server;

try {
  config = loadConfig(process.env);
  fs.mkdirSync(config.codexCwd, { recursive: true });

  runtime = new CodexMcpRuntime({
    command: config.codexBin,
    args: config.codexArgs,
    cwd: config.codexCwd,
    env: config.codexEnv,
    startupTimeoutMs: config.startupTimeoutMs
  });

  await runtime.start();

  server = http.createServer((request, response) => {
    handleRequest(request, response).catch(error => {
      sendError(response, error);
    });
  });

  server.listen(config.port, config.host, () => {
    console.error(`codex-openai-sidecar listening on http://${config.host}:${config.port}`);
  });
}
catch (error) {
  console.error(`codex-openai-sidecar startup failed: ${error?.message || error}`);
  process.exit(1);
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

async function handleRequest(request, response) {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "127.0.0.1"}`);
  if (request.method === "GET" && url.pathname === "/healthz") {
    const running = runtime.isRunning();
    sendJson(response, running ? 200 : 503, {
      status: running ? "ok" : "error",
      endpoint: "/v1/chat/completions",
      codexRuntime: "mcp-server"
    });
    return;
  }
  if (request.method === "POST" && url.pathname === "/v1/chat/completions") {
    requireBearer(request);
    const rawBody = await readBody(request, config.maxBodyBytes);
    let body;
    try {
      body = JSON.parse(rawBody);
    }
    catch (error) {
      throw new HttpError(400, "invalid_json", "Request body must be valid JSON", error);
    }
    const chatRequest = validateChatCompletionRequest(body);
    const prompt = buildCodexPrompt(chatRequest);
    const codexToolArgs = {
      prompt,
      cwd: config.codexCwd,
      sandbox: config.codexSandbox,
      "approval-policy": config.codexApprovalPolicy,
      config: config.codexRuntimeConfig
    };
    if (config.codexModel) {
      codexToolArgs.model = config.codexModel;
    }

    let codexText;
    try {
      codexText = await runtime.runCodex(codexToolArgs, config.requestTimeoutMs);
    }
    catch (error) {
      if (error instanceof McpTimeoutError) {
        await restartRuntimeAfterTimeout();
        throw new HttpError(504, "codex_timeout", error.message || "Codex runtime timed out", error);
      }
      throw new HttpError(502, "codex_runtime_error", error.message || "Codex runtime failed", error);
    }
    const assistant = parseCodexAssistantText(codexText, chatRequest);
    sendJson(response, 200, buildOpenAiChatCompletion(chatRequest, assistant));
    return;
  }
  sendJson(response, 404, {
    error: {
      message: "Not found",
      type: "not_found",
      code: "not_found"
    }
  });
}

function loadConfig(env) {
  const host = env.CODEX_SIDECAR_HOST || "127.0.0.1";
  if (!isLoopbackHost(host) && env.CODEX_SIDECAR_ALLOW_NON_LOOPBACK !== "true") {
    throw new Error("CODEX_SIDECAR_HOST must be loopback unless CODEX_SIDECAR_ALLOW_NON_LOOPBACK=true");
  }

  const bearerToken = env.CODEX_SIDECAR_BEARER;
  if (!bearerToken || !bearerToken.trim()) {
    throw new Error("CODEX_SIDECAR_BEARER is required");
  }

  const codexHome = env.CODEX_SIDECAR_CODEX_HOME;
  if (!codexHome || !codexHome.trim()) {
    throw new Error("CODEX_SIDECAR_CODEX_HOME is required");
  }
  const resolvedCodexHome = path.resolve(codexHome);
  validateCodexHome(resolvedCodexHome, env);

  const port = readInt(env.CODEX_SIDECAR_PORT, 3217);
  const codexBin = env.CODEX_SIDECAR_CODEX_BIN || "codex";
  const codexCwd = path.resolve(env.CODEX_SIDECAR_CODEX_CWD || path.join(os.tmpdir(), "airicraft-codex-planner"));
  const codexEnv = { ...env };
  codexEnv.CODEX_HOME = resolvedCodexHome;
  const codexArgs = ["mcp-server"];
  return {
    host,
    port,
    bearerToken,
    maxBodyBytes: readInt(env.CODEX_SIDECAR_MAX_BODY_BYTES, 8 * 1024 * 1024),
    requestTimeoutMs: readInt(env.CODEX_SIDECAR_REQUEST_TIMEOUT_MS, 120000),
    startupTimeoutMs: readInt(env.CODEX_SIDECAR_STARTUP_TIMEOUT_MS, 15000),
    codexBin,
    codexArgs,
    codexEnv,
    codexCwd,
    codexModel: env.CODEX_SIDECAR_CODEX_MODEL || "",
    codexSandbox: env.CODEX_SIDECAR_CODEX_SANDBOX || "read-only",
    codexApprovalPolicy: env.CODEX_SIDECAR_CODEX_APPROVAL_POLICY || "never",
    codexRuntimeConfig: {
      web_search: "disabled",
      project_doc_max_bytes: 0,
      project_doc_fallback_filenames: [],
      history: {
        persistence: "none"
      },
      features: {
        shell_tool: false
      }
    }
  };
}

async function restartRuntimeAfterTimeout() {
  try {
    await runtime.restart();
  }
  catch (error) {
    console.error(`failed to restart Codex MCP runtime after timeout: ${error?.message || error}`);
  }
}

function isLoopbackHost(host) {
  return host === "127.0.0.1" || host === "localhost" || host === "::1" || host === "[::1]";
}

function validateCodexHome(codexHome, env) {
  const normalHome = env.HOME ? path.resolve(env.HOME, ".codex") : "";
  if (normalHome && codexHome === normalHome && env.CODEX_SIDECAR_ALLOW_DEFAULT_CODEX_HOME !== "true") {
    throw new Error("CODEX_SIDECAR_CODEX_HOME must not point at the default ~/.codex unless CODEX_SIDECAR_ALLOW_DEFAULT_CODEX_HOME=true");
  }
  const stat = fs.existsSync(codexHome) ? fs.statSync(codexHome) : null;
  if (!stat || !stat.isDirectory()) {
    throw new Error("CODEX_SIDECAR_CODEX_HOME must point at an existing directory");
  }
  const configFile = path.join(codexHome, "config.toml");
  if (!fs.existsSync(configFile)) {
    throw new Error("CODEX_SIDECAR_CODEX_HOME must contain config.toml");
  }
}

function readInt(value, fallback) {
  if (value == null || value === "") {
    return fallback;
  }
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function requireBearer(request) {
  if (!config.bearerToken) {
    return;
  }
  const authorization = request.headers.authorization || "";
  if (authorization !== `Bearer ${config.bearerToken}`) {
    throw new HttpError(401, "unauthorized", "Missing or invalid bearer token");
  }
}

function sendJson(response, status, payload) {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body)
  });
  response.end(body);
}

function sendError(response, error) {
  const status = error instanceof HttpError ? error.status : 500;
  const code = error instanceof HttpError ? error.code : "internal_error";
  const message = error?.message || "Internal server error";
  sendJson(response, status, {
    error: {
      message,
      type: code,
      code
    }
  });
}

function shutdown() {
  if (!server) {
    runtime?.stop();
    process.exit(0);
  }
  server.close(() => {
    runtime.stop();
    process.exit(0);
  });
  setTimeout(() => {
    runtime.stop();
    process.exit(0);
  }, 2000).unref();
}
