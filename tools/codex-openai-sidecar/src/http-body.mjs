import { HttpError } from "./openai-adapter.mjs";

export function readBody(request, maxBytes) {
  return new Promise((resolve, reject) => {
    let body = "";
    let bytes = 0;
    request.setEncoding("utf8");
    request.on("data", chunk => {
      bytes += Buffer.byteLength(chunk, "utf8");
      if (bytes > maxBytes) {
        reject(new HttpError(413, "request_too_large", "Request body is too large"));
        request.destroy();
        return;
      }
      body += chunk;
    });
    request.on("end", () => resolve(body));
    request.on("error", reject);
    request.on("close", () => {
      if (request.aborted || !request.complete) {
        reject(new HttpError(499, "client_closed_request", "Client closed request"));
      }
    });
  });
}
