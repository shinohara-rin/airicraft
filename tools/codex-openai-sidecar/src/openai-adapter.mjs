import crypto from "node:crypto";

export class HttpError extends Error {
  constructor(status, code, message, cause = undefined) {
    super(message);
    this.name = "HttpError";
    this.status = status;
    this.code = code;
    this.cause = cause;
  }
}

export function validateChatCompletionRequest(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpError(400, "invalid_request", "Request body must be a JSON object");
  }
  if (value.stream === true) {
    throw new HttpError(400, "unsupported_streaming", "Streaming chat completions are not supported");
  }
  if (!Array.isArray(value.messages) || value.messages.length === 0) {
    throw new HttpError(400, "invalid_messages", "messages must be a non-empty array");
  }
  for (const [index, message] of value.messages.entries()) {
    if (!message || typeof message !== "object" || Array.isArray(message)) {
      throw new HttpError(400, "invalid_message", `messages[${index}] must be an object`);
    }
    if (typeof message.role !== "string" || message.role.length === 0) {
      throw new HttpError(400, "invalid_message_role", `messages[${index}].role must be a string`);
    }
    if (containsUnsupportedImageContent(message.content)) {
      throw new HttpError(400, "unsupported_vision", "Native vision content is not supported by the Codex sidecar");
    }
  }
  if (value.tools !== undefined && !Array.isArray(value.tools)) {
    throw new HttpError(400, "invalid_tools", "tools must be an array when provided");
  }
  if (value.response_format !== undefined) {
    const responseFormat = value.response_format;
    if (!responseFormat || typeof responseFormat !== "object" || Array.isArray(responseFormat)) {
      throw new HttpError(400, "invalid_response_format", "response_format must be an object");
    }
    if (responseFormat.type !== "json_object") {
      throw new HttpError(400, "unsupported_response_format", "Only response_format.type=json_object is supported");
    }
  }
  return value;
}

export function buildCodexPrompt(request) {
  const responseFormat = request.response_format?.type === "json_object" ? "json_object" : "text_or_tools";
  const toolChoice = request.tool_choice ?? "auto";
  const envelope = {
    response_format: request.response_format ?? null,
    tool_choice: toolChoice,
    messages: request.messages,
    tools: request.tools ?? []
  };

  const responseContract = responseFormat === "json_object"
    ? [
        "Return exactly one JSON object and nothing else.",
        "Shape: {\"content\": <json object>}.",
        "The content value must be the JSON object requested by the conversation.",
        "Do not include tool_calls."
      ].join("\n")
    : [
        "Return exactly one JSON object and nothing else.",
        "For a normal assistant reply, use: {\"content\":\"visible reply\",\"tool_calls\":[]}.",
        "For Airicraft tool use, use: {\"content\":null,\"tool_calls\":[{\"name\":\"tool_name\",\"arguments\":{}}]}.",
        "Tool call arguments must be JSON objects that satisfy the matching tool schema.",
        "Do not execute tools yourself. The listed tools are virtual Airicraft function calls only."
      ].join("\n");

  return [
    "You are serving an OpenAI-compatible /v1/chat/completions endpoint for Airicraft.",
    "You are not in a persistent conversation. Treat this request as the complete context for one assistant message.",
    "Never call continuation APIs, never rely on previous Codex thread state, and never operate Minecraft directly.",
    responseContract,
    "OpenAI-compatible request envelope:",
    JSON.stringify(envelope, null, 2)
  ].join("\n\n");
}

export function parseCodexAssistantText(text, request) {
  const object = parseJsonObjectFromText(text);
  const assistant = normalizeAssistantObject(object);
  return validateAssistantForRequest(assistant, request);
}

export function buildOpenAiChatCompletion(request, assistant, options = {}) {
  const toolCalls = assistant.tool_calls ?? [];
  const message = {
    role: "assistant",
    content: toolCalls.length === 0 ? assistant.content : (assistant.content?.trim() ? assistant.content : null)
  };
  if (toolCalls.length > 0) {
    message.tool_calls = toolCalls.map((call, index) => ({
      id: call.id ?? `call_codex_${index + 1}_${crypto.randomUUID().replaceAll("-", "").slice(0, 12)}`,
      type: "function",
      function: {
        name: call.name,
        arguments: JSON.stringify(call.arguments)
      }
    }));
  }

  return {
    id: `chatcmpl-codex-${crypto.randomUUID()}`,
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model: String(request.model ?? options.defaultModel ?? "codex-local-sidecar"),
    choices: [
      {
        index: 0,
        message,
        finish_reason: toolCalls.length > 0 ? "tool_calls" : "stop"
      }
    ]
  };
}

function validateAssistantForRequest(assistant, request) {
  const rawToolCalls = assistant.tool_calls ?? [];
  if (!Array.isArray(rawToolCalls)) {
    throw new HttpError(502, "invalid_codex_output", "Codex tool_calls must be an array");
  }

  if (request.response_format?.type === "json_object") {
    if (rawToolCalls.length > 0) {
      throw new HttpError(502, "invalid_codex_output", "Codex returned tool_calls for a json_object response");
    }
    return {
      content: normalizeJsonObjectContent(assistant.content),
      tool_calls: []
    };
  }

  const toolCalls = rawToolCalls.map((call, index) => normalizeToolCall(call, index, request));
  if (toolCalls.length > 0) {
    return {
      content: typeof assistant.content === "string" ? assistant.content : "",
      tool_calls: toolCalls
    };
  }
  return {
    content: assistant.content == null ? "" : String(assistant.content),
    tool_calls: []
  };
}

function normalizeAssistantObject(object) {
  if (object.message && typeof object.message === "object" && !Array.isArray(object.message)) {
    return normalizeAssistantObject(object.message);
  }
  return {
    content: object.content ?? null,
    tool_calls: object.tool_calls ?? object.toolCalls ?? []
  };
}

function normalizeJsonObjectContent(value) {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return JSON.stringify(value);
  }
  if (typeof value !== "string") {
    throw new HttpError(502, "invalid_codex_output", "Codex json_object content must be an object or JSON object string");
  }
  const parsed = parseJsonObjectFromText(value);
  return JSON.stringify(parsed);
}

function normalizeToolCall(rawCall, index, request) {
  if (!rawCall || typeof rawCall !== "object" || Array.isArray(rawCall)) {
    throw new HttpError(502, "invalid_codex_output", `tool_calls[${index}] must be an object`);
  }

  const functionObject = rawCall.function && typeof rawCall.function === "object" && !Array.isArray(rawCall.function)
    ? rawCall.function
    : rawCall;
  const name = functionObject.name;
  if (typeof name !== "string" || name.trim() === "") {
    throw new HttpError(502, "invalid_codex_output", `tool_calls[${index}] is missing function name`);
  }

  const allowedNames = requestToolNames(request);
  if (allowedNames.size > 0 && !allowedNames.has(name)) {
    throw new HttpError(502, "invalid_codex_output", `Codex returned unknown tool call: ${name}`);
  }

  const args = functionObject.arguments ?? {};
  const argumentsObject = typeof args === "string" ? parseJsonObjectFromText(args) : args;
  if (!argumentsObject || typeof argumentsObject !== "object" || Array.isArray(argumentsObject)) {
    throw new HttpError(502, "invalid_codex_output", `tool_calls[${index}].arguments must be a JSON object`);
  }

  return {
    id: typeof rawCall.id === "string" && rawCall.id.trim() ? rawCall.id : undefined,
    name,
    arguments: argumentsObject
  };
}

function requestToolNames(request) {
  const names = new Set();
  for (const tool of request.tools ?? []) {
    const name = tool?.function?.name;
    if (typeof name === "string" && name.trim() !== "") {
      names.add(name);
    }
  }
  return names;
}

function containsUnsupportedImageContent(content) {
  if (!Array.isArray(content)) {
    return false;
  }
  return content.some(part => {
    if (!part || typeof part !== "object") {
      return false;
    }
    return part.type === "image_url" || part.type === "input_image" || part.image_url !== undefined;
  });
}

export function parseJsonObjectFromText(text) {
  if (typeof text !== "string") {
    throw new HttpError(502, "invalid_codex_output", "Codex output must be text");
  }
  const trimmed = stripMarkdownFence(text.trim());
  try {
    const parsed = JSON.parse(trimmed);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return parsed;
    }
  }
  catch {
    // Fall through to balanced-object extraction below.
  }

  const extracted = extractFirstBalancedJsonObject(trimmed);
  if (extracted == null) {
    throw new HttpError(502, "invalid_codex_output", "Codex output did not contain a JSON object");
  }
  try {
    const parsed = JSON.parse(extracted);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return parsed;
    }
  }
  catch (error) {
    throw new HttpError(502, "invalid_codex_output", "Codex output contained invalid JSON", error);
  }
  throw new HttpError(502, "invalid_codex_output", "Codex output JSON must be an object");
}

function stripMarkdownFence(text) {
  if (!text.startsWith("```")) {
    return text;
  }
  const firstNewline = text.indexOf("\n");
  if (firstNewline < 0) {
    return text;
  }
  let body = text.slice(firstNewline + 1);
  if (body.endsWith("```")) {
    body = body.slice(0, -3);
  }
  return body.trim();
}

function extractFirstBalancedJsonObject(text) {
  let start = -1;
  let depth = 0;
  let inString = false;
  let escape = false;

  for (let index = 0; index < text.length; index += 1) {
    const ch = text[index];
    if (start < 0) {
      if (ch === "{") {
        start = index;
        depth = 1;
      }
      continue;
    }

    if (escape) {
      escape = false;
      continue;
    }
    if (ch === "\\") {
      escape = true;
      continue;
    }
    if (ch === "\"") {
      inString = !inString;
      continue;
    }
    if (inString) {
      continue;
    }
    if (ch === "{") {
      depth += 1;
      continue;
    }
    if (ch === "}") {
      depth -= 1;
      if (depth === 0) {
        return text.slice(start, index + 1);
      }
    }
  }
  return null;
}
