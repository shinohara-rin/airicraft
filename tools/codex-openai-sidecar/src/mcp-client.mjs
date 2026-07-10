import { spawn } from "node:child_process";

export class McpTimeoutError extends Error {
  constructor(message) {
    super(message);
    this.name = "McpTimeoutError";
  }
}

export class CodexMcpRuntime {
  constructor(options = {}) {
    this.command = options.command ?? "codex";
    this.args = options.args ?? ["mcp-server"];
    this.cwd = options.cwd ?? process.cwd();
    this.env = options.env ?? process.env;
    this.startupTimeoutMs = options.startupTimeoutMs ?? 15000;
    this.child = null;
    this.nextId = 1;
    this.pending = new Map();
    this.stdoutBuffer = "";
    this.stderrTail = "";
  }

  async start() {
    if (this.child) {
      return;
    }
    this.child = spawn(this.command, this.args, {
      cwd: this.cwd,
      env: this.env,
      stdio: ["pipe", "pipe", "pipe"]
    });
    this.child.stdout.setEncoding("utf8");
    this.child.stderr.setEncoding("utf8");
    this.child.stdout.on("data", chunk => this.#onStdout(chunk));
    this.child.stderr.on("data", chunk => this.#onStderr(chunk));
    this.child.on("exit", (code, signal) => this.#onExit(code, signal));
    this.child.on("error", error => this.#onError(error));

    await this.request("initialize", {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: {
        name: "airicraft-codex-openai-sidecar",
        version: "0.1.0"
      }
    }, this.startupTimeoutMs);
    this.notify("notifications/initialized", {});
    const tools = await this.request("tools/list", {}, this.startupTimeoutMs);
    const listedTools = Array.isArray(tools?.tools) ? tools.tools : [];
    const names = new Set(listedTools.map(tool => tool?.name).filter(Boolean));
    if (!names.has("codex")) {
      throw new Error("Codex MCP server did not expose the codex tool");
    }
  }

  async runCodex(argumentsObject, timeoutMs) {
    const result = await this.request("tools/call", {
      name: "codex",
      arguments: argumentsObject
    }, timeoutMs);
    if (result.isError) {
      throw new Error(extractMcpText(result) || "Codex MCP tool returned an error");
    }
    return extractCodexToolText(result);
  }

  async restart() {
    this.stop("Codex MCP process restarted");
    await new Promise(resolve => setTimeout(resolve, 50));
    await this.start();
  }

  isRunning() {
    return Boolean(this.child && !this.child.killed && this.child.exitCode == null);
  }

  notify(method, params = {}) {
    this.#write({ jsonrpc: "2.0", method, params });
  }

  request(method, params = {}, timeoutMs = 30000) {
    if (!this.child || this.child.killed || !this.child.stdin.writable) {
      return Promise.reject(new Error("Codex MCP process is not running"));
    }
    const id = this.nextId;
    this.nextId += 1;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new McpTimeoutError(`Codex MCP request timed out: ${method}`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.#write({ jsonrpc: "2.0", id, method, params });
    });
  }

  stop(reason = "Codex MCP process stopped") {
    if (!this.child) {
      return;
    }
    const child = this.child;
    this.child = null;
    for (const [id, pending] of this.pending.entries()) {
      clearTimeout(pending.timer);
      pending.reject(new Error(reason));
      this.pending.delete(id);
    }
    if (!child.killed) {
      child.kill("SIGTERM");
    }
  }

  #write(message) {
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  #onStdout(chunk) {
    this.stdoutBuffer += chunk;
    while (true) {
      const newline = this.stdoutBuffer.indexOf("\n");
      if (newline < 0) {
        return;
      }
      const line = this.stdoutBuffer.slice(0, newline).trim();
      this.stdoutBuffer = this.stdoutBuffer.slice(newline + 1);
      if (line.length === 0) {
        continue;
      }
      let message;
      try {
        message = JSON.parse(line);
      }
      catch {
        continue;
      }
      if (!message || typeof message !== "object" || message.id == null || !this.pending.has(message.id)) {
        continue;
      }
      const pending = this.pending.get(message.id);
      this.pending.delete(message.id);
      clearTimeout(pending.timer);
      if (message.error) {
        pending.reject(new Error(message.error.message ?? JSON.stringify(message.error)));
      }
      else {
        pending.resolve(message.result);
      }
    }
  }

  #onStderr(chunk) {
    this.stderrTail = `${this.stderrTail}${chunk}`.slice(-8000);
  }

  #onExit(code, signal) {
    const detail = signal ? `signal ${signal}` : `exit code ${code}`;
    const message = this.stderrTail.trim()
      ? `Codex MCP process exited with ${detail}: ${this.stderrTail.trim()}`
      : `Codex MCP process exited with ${detail}`;
    for (const [id, pending] of this.pending.entries()) {
      clearTimeout(pending.timer);
      pending.reject(new Error(message));
      this.pending.delete(id);
    }
    this.child = null;
  }

  #onError(error) {
    const message = `Codex MCP process failed to start or encountered an error: ${error.message}`;
    for (const [id, pending] of this.pending.entries()) {
      clearTimeout(pending.timer);
      pending.reject(new Error(message));
      this.pending.delete(id);
    }
    this.child = null;
  }
}

export function extractCodexToolText(result) {
  const structured = result.structuredContent ?? result.structured_content;
  if (structured && typeof structured === "object") {
    if (typeof structured.content === "string") {
      return structured.content;
    }
    if (typeof structured.text === "string") {
      return structured.text;
    }
  }

  const text = extractMcpText(result);
  if (!text) {
    throw new Error("Codex MCP result did not include text content");
  }
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === "object" && typeof parsed.threadId === "string" && typeof parsed.content === "string") {
      return parsed.content;
    }
  }
  catch {
    // Plain text final answers are valid; the OpenAI adapter will validate them.
  }
  return text;
}

function extractMcpText(result) {
  if (typeof result.content === "string") {
    return result.content;
  }
  if (!Array.isArray(result.content)) {
    return "";
  }
  return result.content
    .filter(item => item && item.type === "text" && typeof item.text === "string")
    .map(item => item.text)
    .join("\n");
}
