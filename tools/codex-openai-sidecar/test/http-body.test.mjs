import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import { readBody } from "../src/http-body.mjs";
import { HttpError } from "../src/openai-adapter.mjs";

class FakeRequest extends EventEmitter {
  constructor() {
    super();
    this.aborted = false;
    this.complete = false;
  }

  setEncoding() {}

  destroy() {
    this.aborted = true;
    this.emit("close");
  }
}

test("rejects when the client closes an incomplete request", async () => {
  const request = new FakeRequest();
  const body = readBody(request, 1024);

  request.aborted = true;
  request.emit("close");

  await assert.rejects(
    body,
    error => error instanceof HttpError
      && error.status === 499
      && error.code === "client_closed_request"
  );
});
