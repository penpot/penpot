import { parentPort } from "node:worker_threads";
import { sanitizeSvgSync } from "./services/svg.js";
import { ProcessingError } from "./middleware/error-handler.js";

// Entry point of the SVG sanitization worker. `svg-pool` spawns this file and
// sends the SVG to sanitize through `postMessage`; the answer is always a
// plain, structured-cloneable object, never a class instance.
const port = parentPort;
if (port === null) {
  throw new Error("svg-worker must be started as a worker thread");
}

port.on("message", (svg: string) => {
  try {
    port.postMessage({ ok: true, clean: sanitizeSvgSync(svg) });
  } catch (err) {
    if (err instanceof ProcessingError) {
      port.postMessage({ ok: false, statusCode: err.statusCode, body: err.errorBody });
    } else {
      port.postMessage({
        ok: false,
        statusCode: 503,
        body: { type: "internal", code: "svg-sanitization-failed" },
      });
    }
  }
});
