import assert from "node:assert/strict";
import test from "node:test";
import {
  HttpError,
  buildCodexPrompt,
  buildOpenAiChatCompletion,
  parseCodexAssistantText,
  validateChatCompletionRequest
} from "../src/openai-adapter.mjs";

const requestWithTool = {
  model: "codex-local-sidecar",
  messages: [
    { role: "system", content: "system" },
    { role: "user", content: "Check inventory" }
  ],
  tools: [
    {
      type: "function",
      function: {
        name: "inspect_inventory",
        description: "Inspect inventory",
        parameters: { type: "object", properties: { prompt: { type: "string" } } }
      }
    }
  ],
  tool_choice: "auto"
};

test("validates unsupported request features", () => {
  assert.throws(
    () => validateChatCompletionRequest({ model: "x", stream: true, messages: [{ role: "user", content: "hi" }] }),
    error => error instanceof HttpError && error.status === 400 && error.code === "unsupported_streaming"
  );
  assert.throws(
    () => validateChatCompletionRequest({
      model: "x",
      messages: [{ role: "user", content: [{ type: "image_url", image_url: { url: "data:image/png;base64,AA==" } }] }]
    }),
    error => error instanceof HttpError && error.status === 400 && error.code === "unsupported_vision"
  );
});

test("builds a stateless Codex prompt", () => {
  const prompt = buildCodexPrompt(requestWithTool);

  assert.match(prompt, /not in a persistent conversation/);
  assert.match(prompt, /tool_calls/);
  assert.match(prompt, /inspect_inventory/);
  assert.doesNotMatch(prompt, /codex-reply/);
});

test("maps Codex tool calls to OpenAI chat completion shape", () => {
  const assistant = parseCodexAssistantText(
    "{\"tool_calls\":[{\"name\":\"inspect_inventory\",\"arguments\":{\"prompt\":\"count seeds\"}}]}",
    requestWithTool
  );

  const response = buildOpenAiChatCompletion(requestWithTool, assistant);
  const message = response.choices[0].message;
  assert.equal(message.content, null);
  assert.equal(message.tool_calls[0].type, "function");
  assert.equal(message.tool_calls[0].function.name, "inspect_inventory");
  assert.deepEqual(JSON.parse(message.tool_calls[0].function.arguments), { prompt: "count seeds" });
  assert.equal(response.choices[0].finish_reason, "tool_calls");
});

test("rejects unknown tool calls before returning to Airicraft", () => {
  assert.throws(
    () => parseCodexAssistantText("{\"tool_calls\":[{\"name\":\"dance\",\"arguments\":{}}]}", requestWithTool),
    error => error instanceof HttpError && error.status === 502 && error.code === "invalid_codex_output"
  );
});

test("maps json_object compaction content to assistant content string", () => {
  const request = {
    model: "codex-local-sidecar",
    response_format: { type: "json_object" },
    messages: [{ role: "user", content: "compact" }]
  };

  const assistant = parseCodexAssistantText(
    "```json\n{\"content\":{\"time_anchor\":\"now\",\"recent_timeline\":[\"started\"]}}\n```",
    request
  );
  const response = buildOpenAiChatCompletion(request, assistant);

  assert.equal(response.choices[0].message.tool_calls, undefined);
  assert.deepEqual(JSON.parse(response.choices[0].message.content), {
    time_anchor: "now",
    recent_timeline: ["started"]
  });
});
