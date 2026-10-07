import { isMainThread } from "node:worker_threads";
import createDOMPurify, { type WindowLike } from "dompurify";
import { JSDOM } from "jsdom";
import { throwValidation, throwRestriction, throwProcessing } from "./errors.js";
import { ProcessingError } from "../middleware/error-handler.js";
import { createLogger } from "../logger.js";
import { getSvgPoolOptions, runInSvgPool } from "../svg-pool.js";

const logger = createLogger("svg");
const SVG_NAMESPACE = "http://www.w3.org/2000/svg";

let svgMaxSize = 2 * 1024 * 1024;

export function configureSvgLimits(opts: { maxSize: number }): void {
  svgMaxSize = opts.maxSize;
}

export function getSvgMaxSize(): number {
  return svgMaxSize;
}

// A well-formed SVG document may start with an XML declaration or a DOCTYPE.
// DOMPurify parses the SVG as XML, and both make the XML parser bail out and
// the sanitizer return nothing, so they are stripped first. Stripping the
// DOCTYPE is also what keeps its entities from ever being declared. A prolog
// the stripper misses still fails closed: DOMPurify returns an empty document
// and `sanitizeSvgSync` rejects it.
function stripProlog(svg: string): string {
  return svg
    .replace(/^\uFEFF/, "")
    .replace(/^\s*<\?xml[\s\S]*?\?>/, "")
    .replace(/^\s*<!DOCTYPE[^>[]*(\[[\s\S]*?\])?[^>]*>/i, "")
    .trimStart();
}

const SVG_ROOT = /^<svg[\s/>]/i;

/**
 * Sanitize an SVG document synchronously with DOMPurify over jsdom and return
 * the clean SVG as UTF-8 text.
 *
 * DOMPurify works from an allowlist: every element and attribute it does not
 * explicitly allow is removed, so it fails closed. It is given the SVG profile
 * and the SVG namespace, so it parses (and therefore judges) the document as an
 * SVG document, the way a browser does when it loads a standalone `.svg`.
 *
 * A fresh jsdom window is created for every call and closed afterwards:
 * DOMPurify keeps state on its window, and reusing one window in a long-lived
 * process leaks memory and degrades latency without bound.
 *
 * This is the CPU-bound core. The HTTP route does not call it directly: it runs
 * in a `worker_threads` worker through `sanitizeSvg`, so the service's event
 * loop is never blocked by it.
 *
 * Throws a `ProcessingError` (413 `svg-too-large`) when the input exceeds the
 * configured limit, (400 `invalid-svg-file`) when the input is not a
 * well-formed SVG document, and (503 `svg-sanitization-failed`) when anything
 * else goes wrong.
 */
export function sanitizeSvgSync(svg: string): string {
  const size = Buffer.byteLength(svg, "utf8");
  if (size > svgMaxSize) {
    throwRestriction("svg-too-large", `SVG size ${size} exceeds the maximum of ${svgMaxSize} bytes`);
  }

  const dom = new JSDOM("");
  try {
    const purify = createDOMPurify(dom.window as unknown as WindowLike);
    const clean = purify.sanitize(stripProlog(svg), {
      USE_PROFILES: { svg: true, svgFilters: true },
      NAMESPACE: SVG_NAMESPACE,
    });

    const normalized = clean.trimStart();
    if (!SVG_ROOT.test(normalized)) {
      throwValidation("invalid-svg-file", "SVG sanitization produced no svg root");
    }
    return normalized;
  } catch (err) {
    if (err instanceof ProcessingError) {
      throw err;
    }
    // A failure of the sanitizer itself (a bug, an OOM, a DOMPurify regression)
    // is not the user's file being invalid: report it as a service failure and
    // keep the technical detail out of the hint. Inside a worker this is not
    // logged: the pool logs it on the main thread when the result comes back,
    // so a worker never initializes the (transport-spawning) logger.
    if (isMainThread) {
      logger.error({ err }, "unexpected svg sanitization failure");
    }
    throwProcessing("svg-sanitization-failed", "SVG sanitization failed unexpectedly");
  } finally {
    dom.window.close();
  }
}

/**
 * Sanitize an SVG document, off the event loop when a worker pool is
 * configured. Falls back to running `sanitizeSvgSync` inline when the pool is
 * disabled (`PENPOT_MEDIA_PROCESSOR_SVG_WORKERS=0`) or not configured, which is
 * what tests use.
 */
export function sanitizeSvg(svg: string): Promise<string> {
  const options = getSvgPoolOptions();
  if (options === null || options.workers <= 0) {
    return Promise.resolve().then(() => sanitizeSvgSync(svg));
  }
  return runInSvgPool(svg);
}
