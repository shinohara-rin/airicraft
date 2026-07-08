import assert from "node:assert/strict";
import test from "node:test";
import { CodexMcpRuntime, McpTimeoutError, extractCodexToolText } from "../src/mcp-client.mjs";

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
