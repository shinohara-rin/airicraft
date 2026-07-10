import assert from "node:assert/strict";
import test from "node:test";
import { CodexMcpRuntime, McpTimeoutError, extractCodexToolText } from "../src/mcp-client.mjs";

function createFakeRuntime(toolsResult, options = {}) {
  const responder = `
    const readline = require("node:readline");
    const toolsResult = ${JSON.stringify(toolsResult)};
    const input = readline.createInterface({ input: process.stdin });
    input.on("line", line => {
      const message = JSON.parse(line);
      if (message.method === "initialize") {
        ${options.emitNull ? 'process.stdout.write("null\\n");' : ""}
        process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: {} }) + "\\n");
      }
      else if (message.method === "tools/list") {
        process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: toolsResult }) + "\\n");
      }
    });
  `;
  return new CodexMcpRuntime({
    command: process.execPath,
    args: ["-e", responder],
    startupTimeoutMs: 1000
  });
}

test("extracts structured Codex content", () => {
  assert.equal(
    extractCodexToolText({ structuredContent: { threadId: "thread-1", content: "{\"content\":\"ok\"}" } }),
    "{\"content\":\"ok\"}"
  );
});

test("extracts Codex content from MCP text payload", () => {
  assert.equal(
    extractCodexToolText({
      content: [
        {
          type: "text",
          text: "{\"threadId\":\"thread-1\",\"content\":\"{\\\"content\\\":\\\"ok\\\"}\"}"
        }
      ]
    }),
    "{\"content\":\"ok\"}"
  );
});

test("passes through raw MCP text", () => {
  assert.equal(
    extractCodexToolText({ content: [{ type: "text", text: "{\"content\":\"ok\"}" }] }),
    "{\"content\":\"ok\"}"
  );
});

test("reports runtime as stopped before process start", () => {
  const runtime = new CodexMcpRuntime();
  assert.equal(runtime.isRunning(), false);
});

test("exports a stable timeout error type", () => {
  const error = new McpTimeoutError("timeout");
  assert.equal(error.name, "McpTimeoutError");
  assert.equal(error.message, "timeout");
});

test("rejects gracefully when the Codex process cannot be spawned", async () => {
  const runtime = new CodexMcpRuntime({
    command: "/definitely/missing/codex",
    startupTimeoutMs: 1000
  });

  await assert.rejects(
    runtime.start(),
    /Codex MCP process failed to start or encountered an error:.*ENOENT/
  );
  assert.equal(runtime.isRunning(), false);
});

test("ignores non-object JSON messages from MCP stdout", async () => {
  const runtime = createFakeRuntime({ tools: [{ name: "codex" }] }, { emitNull: true });

  try {
    await runtime.start();
    assert.equal(runtime.isRunning(), true);
  }
  finally {
    runtime.stop();
  }
});

test("rejects a null tools/list result without a TypeError", async () => {
  const runtime = createFakeRuntime(null);

  try {
    await assert.rejects(
      runtime.start(),
      /Codex MCP server did not expose the codex tool/
    );
  }
  finally {
    runtime.stop();
  }
});
