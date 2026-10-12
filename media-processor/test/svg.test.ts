import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import request from "supertest";
import express from "express";
import { sanitizeSvg, sanitizeSvgSync, configureSvgLimits } from "../src/services/svg.js";
import { createSvgRoutes } from "../src/routes/svg.js";
import { errorHandler, ProcessingError } from "../src/middleware/error-handler.js";
import { timeoutMiddleware } from "../src/middleware/timeout.js";
import { sharedKeyAuth } from "../src/middleware/auth.js";
import { createQueueMiddleware } from "../src/middleware/queue.js";
import { configureUploadLimits } from "../src/upload.js";

const SVG_NS = "http://www.w3.org/2000/svg";
const DEFAULT_MAX_SIZE = 2 * 1024 * 1024;

function svg(inner: string, attrs = `xmlns="${SVG_NS}" width="100" height="100"`): string {
  return `<svg ${attrs}>${inner}</svg>`;
}

// Outputs must never carry an event handler, a javascript: URL or a script
// element, whatever the input was.
function expectNoDangerousOutput(out: string): void {
  expect(out).not.toMatch(/\son\w+\s*=/i);
  expect(out).not.toMatch(/<script/i);
  expect(out).not.toContain("javascript:");
}

function captureError(fn: () => unknown): unknown {
  try {
    fn();
    return undefined;
  } catch (err) {
    return err;
  }
}

function expectProcessingError(fn: () => unknown, status: number, code: string): void {
  const err = captureError(fn);
  expect(err).toBeInstanceOf(ProcessingError);
  const processingError = err as ProcessingError;
  expect(processingError.statusCode).toBe(status);
  expect(processingError.errorBody.code).toBe(code);
}

// ---------------------------------------------------------------------------
// sanitizeSvg: malicious corpus
// ---------------------------------------------------------------------------

const MALICIOUS: Array<{ name: string; svg: string; forbidden: RegExp }> = [
  {
    name: "script element",
    svg: svg(`<script>alert("xss")</script><rect width="50" height="50"/>`),
    forbidden: /<script/i,
  },
  {
    name: "namespace-prefixed script",
    svg: `<svg xmlns="${SVG_NS}" xmlns:x="${SVG_NS}"><x:script>alert("xss")</x:script><rect width="50" height="50"/></svg>`,
    forbidden: /<x:script/i,
  },
  {
    name: "arbitrary-prefixed script",
    svg: `<svg xmlns="${SVG_NS}" xmlns:p="${SVG_NS}"><p:script>alert("xss")</p:script><rect width="50" height="50"/></svg>`,
    forbidden: /<p:script/i,
  },
  {
    name: "javascript href",
    svg: svg(`<a href="javascript:alert('xss')"><rect width="50" height="50"/></a>`),
    forbidden: /javascript:/i,
  },
  {
    name: "javascript xlink href",
    svg: `<svg xmlns="${SVG_NS}" xmlns:xlink="http://www.w3.org/1999/xlink"><a xlink:href="javascript:alert('xss')"><rect width="50" height="50"/></a></svg>`,
    forbidden: /javascript:/i,
  },
  {
    name: "onload attribute",
    svg: svg(`<rect onload="alert('xss')" width="50" height="50"/>`),
    forbidden: /onload/i,
  },
  {
    name: "onclick attribute",
    svg: svg(`<rect onclick="alert('xss')" width="50" height="50"/>`),
    forbidden: /onclick/i,
  },
  {
    name: "foreignObject",
    svg: svg(`<foreignObject width="100" height="100"><body><script>alert("xss")</script></body></foreignObject>`),
    forbidden: /foreignObject/i,
  },
  {
    name: "set element",
    svg: svg(`<set attributeName="onload" to="alert('xss')"/><rect width="50" height="50"/>`),
    forbidden: /<set[\s/>]/i,
  },
  {
    name: "animate element",
    svg: svg(`<animate attributeName="onload" to="alert('xss')"/><rect width="50" height="50"/>`),
    forbidden: /<animate[\s/>]/i,
  },
  {
    name: "use element",
    svg: svg(`<use href="#evil"/><rect width="50" height="50"/>`),
    forbidden: /<use[\s/>]/i,
  },
];

describe("sanitizeSvg: removes dangerous content", () => {
  it.each(MALICIOUS)("removes $name", ({ svg: dirty, forbidden }) => {
    const clean = sanitizeSvgSync(dirty);
    expect(clean).toMatch(/^<svg[\s/>]/i);
    expect(clean).not.toMatch(forbidden);
    expectNoDangerousOutput(clean);
  });
});

// ---------------------------------------------------------------------------
// sanitizeSvg: legitimate content survives
// ---------------------------------------------------------------------------

describe("sanitizeSvg: keeps legitimate SVG", () => {
  it("keeps gradients, filters, clipPath, mask, style and text", () => {
    const dirty = svg(
      `<defs>` +
        `<linearGradient id="g"><stop offset="0" stop-color="red"/></linearGradient>` +
        `<clipPath id="c"><rect width="5" height="5"/></clipPath>` +
        `<filter id="f"><feGaussianBlur stdDeviation="1"/></filter>` +
        `<mask id="m"><rect width="5" height="5"/></mask>` +
        `</defs>` +
        `<style>.a{fill:red}</style>` +
        `<rect class="a" width="5" height="5" fill="url(#g)" clip-path="url(#c)" mask="url(#m)" filter="url(#f)"/>` +
        `<text x="1" y="9"><tspan>hi</tspan></text>`,
      `xmlns="${SVG_NS}" viewBox="0 0 10 10"`
    );
    const clean = sanitizeSvgSync(dirty);
    expect(clean).toMatch(/^<svg[\s/>]/i);
    expect(clean).toContain("<linearGradient");
    expect(clean).toContain("<stop");
    expect(clean).toContain("<clipPath");
    expect(clean).toContain("<feGaussianBlur");
    expect(clean).toContain("<mask");
    expect(clean).toContain("<style>");
    expect(clean).toContain("<tspan");
    expect(clean).toContain('viewBox="0 0 10 10"');
  });

  it("keeps a safe relative/absolute href", () => {
    const clean = sanitizeSvgSync(svg(`<image href="https://example.com/a.png" width="5" height="5"/>`));
    expect(clean).toContain('href="https://example.com/a.png"');
  });

  it("accepts a document with a leading UTF-8 BOM", () => {
    const clean = sanitizeSvgSync(`\uFEFF${svg(`<rect width="5" height="5"/>`)}`);
    expect(clean).toMatch(/^<svg[\s/>]/i);
  });

  it("accepts a document with an XML declaration and leading whitespace", () => {
    const clean = sanitizeSvgSync(
      `<?xml version="1.0" encoding="UTF-8"?>\n<svg xmlns="${SVG_NS}"><rect width="5" height="5"/></svg>`
    );
    expect(clean).toMatch(/^<svg[\s/>]/i);
    expect(clean).toContain("<rect");
  });

  it("accepts a document with a DOCTYPE", () => {
    const dirty = `<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">${svg(`<rect width="5" height="5"/>`)}`;
    const clean = sanitizeSvgSync(dirty);
    expect(clean).toMatch(/^<svg[\s/>]/i);
    expect(clean).toContain("<rect");
  });

  it("does not resolve entities declared in a DOCTYPE internal subset", () => {
    const dirty = `<!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>${svg(`<rect width="5" height="5"/>`)}`;
    const clean = sanitizeSvgSync(dirty);
    expect(clean).not.toContain("passwd");
    expect(clean).not.toContain("&xxe;");
  });
});

// ---------------------------------------------------------------------------
// sanitizeSvg: rejects what is not a well-formed SVG document
// ---------------------------------------------------------------------------

describe("sanitizeSvg: rejects invalid input", () => {
  const INVALID: Array<{ name: string; svg: string }> = [
    { name: "empty input", svg: "" },
    { name: "plain text", svg: "this is not an svg" },
    { name: "malformed xml", svg: "<svg><g></svg>" },
    { name: "html document", svg: "<html><body>hi</body></html>" },
    { name: "text before the root", svg: `hello${svg(`<rect/>`)}` },
    { name: "undefined entity", svg: svg(`<text>&xxe;</text>`) },
  ];

  it.each(INVALID)("rejects $name with invalid-svg-file", ({ svg: dirty }) => {
    expectProcessingError(() => sanitizeSvgSync(dirty), 400, "invalid-svg-file");
  });
});

// ---------------------------------------------------------------------------
// sanitizeSvg: resource limits and stability
// ---------------------------------------------------------------------------

describe("sanitizeSvg: limits and stability", () => {
  afterEach(() => configureSvgLimits({ maxSize: DEFAULT_MAX_SIZE }));

  it("rejects an input larger than the configured maximum", () => {
    configureSvgLimits({ maxSize: 100 });
    const big = svg(`<rect width="5" height="5"/>${"<!-- pad -->".repeat(20)}`);
    expectProcessingError(() => sanitizeSvgSync(big), 413, "svg-too-large");
  });

  it("stays stable over many consecutive calls", () => {
    const dirty = svg(`<rect width="5" height="5"/>`);
    for (let i = 0; i < 150; i++) {
      expect(sanitizeSvgSync(dirty)).toMatch(/^<svg[\s/>]/i);
    }
  });
});

// ---------------------------------------------------------------------------
// sanitizeSvg: async wrapper and internal failures
// ---------------------------------------------------------------------------

describe("sanitizeSvg: async wrapper", () => {
  it("runs inline when no pool is configured", async () => {
    const clean = await sanitizeSvg(svg(`<script>alert("xss")</script><rect width="5" height="5"/>`));
    expect(clean).toMatch(/^<svg[\s/>]/i);
    expect(clean).not.toMatch(/<script/i);
  });
});

describe("sanitizeSvgSync: internal failures", () => {
  it("maps an unexpected failure to 503 svg-sanitization-failed", () => {
    // A Uint8Array passes the byte-length check and then breaks `stripProlog`
    // (it has no `.replace`), which is not a ProcessingError.
    const err = captureError(() => sanitizeSvgSync(new Uint8Array([60, 115]) as unknown as string));
    expect(err).toBeInstanceOf(ProcessingError);
    const processingError = err as ProcessingError;
    expect(processingError.statusCode).toBe(503);
    expect(processingError.errorBody.code).toBe("svg-sanitization-failed");
    expect(processingError.errorBody.hint ?? "").not.toContain("replace");
  });
});

// ---------------------------------------------------------------------------
// sanitizeSvg: cross-mode corpus
// ---------------------------------------------------------------------------

describe("sanitizeSvgSync: corpus decisions", () => {
  it("keeps a data: URI in an image href (pins the cross-mode behavior)", () => {
    const dirty = svg(`<image href="data:image/png;base64,iVBORw0KGgo=" width="5" height="5"/>`);
    const clean = sanitizeSvgSync(dirty);
    // Observed with DOMPurify 3.4.x and the svg profile: data: URIs survive.
    expect(clean).toContain("data:image/png;base64");
  });
});

// ---------------------------------------------------------------------------
// POST /api/svg/sanitize
// ---------------------------------------------------------------------------

// Build the multipart body exactly like the JVM backend does in
// `app.media.remote/build-multipart-stream`: field `file`, filename `file`,
// the part content type as the declared mtype, and the `----PenpotBoundary<ms>`
// boundary. This pins the wire contract from the service side.
function backendMultipartBody(boundary: string, svgText: string): string {
  return (
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="file"; filename="file"\r\n` +
    `Content-Type: image/svg+xml\r\n` +
    `\r\n` +
    svgText +
    `\r\n--${boundary}--\r\n`
  );
}

function createTestApp() {
  const app = express();
  // Low threshold to exercise the disk-storage path of the upload.
  configureUploadLimits({ maxFileSize: 10 * 1024 * 1024, memoryThreshold: 10 });
  const queueMiddleware = createQueueMiddleware(10);

  app.use(timeoutMiddleware(5000));
  app.use("/api/svg", sharedKeyAuth("test-key"), queueMiddleware, createSvgRoutes());
  app.use(errorHandler);

  return app;
}

describe("POST /api/svg/sanitize", () => {
  let app: ReturnType<typeof createTestApp>;

  beforeAll(() => {
    app = createTestApp();
  });

  afterAll(() => {
    configureSvgLimits({ maxSize: DEFAULT_MAX_SIZE });
  });

  it("returns 200 with the sanitized SVG", async () => {
    const dirty = svg(`<script>alert("xss")</script><rect width="5" height="5"/>`);
    const res = await request(app)
      .post("/api/svg/sanitize")
      .set("x-shared-key", "test-key")
      .attach("file", Buffer.from(dirty, "utf8"), { filename: "x.svg", contentType: "image/svg+xml" });

    expect(res.status).toBe(200);
    expect(res.headers["content-type"]).toContain("image/svg+xml");
    const body = Buffer.isBuffer(res.body) ? res.body.toString("utf8") : res.text;
    expect(body).toMatch(/^<svg[\s/>]/i);
    expectNoDangerousOutput(body);
    expect(body).toContain("<rect");
  });

  it("returns 403 without the shared key", async () => {
    const res = await request(app)
      .post("/api/svg/sanitize")
      .attach("file", Buffer.from(svg(`<rect/>`), "utf8"), { filename: "x.svg", contentType: "image/svg+xml" });

    expect(res.status).toBe(403);
  });

  it("returns 400 without a file", async () => {
    const res = await request(app).post("/api/svg/sanitize").set("x-shared-key", "test-key");
    expect(res.status).toBe(400);
    expect(res.body.code).toBe("invalid-svg-file");
  });

  it("returns 400 when the svg is malformed", async () => {
    const res = await request(app)
      .post("/api/svg/sanitize")
      .set("x-shared-key", "test-key")
      .attach("file", Buffer.from("this is not an svg", "utf8"), {
        filename: "x.svg",
        contentType: "image/svg+xml",
      });

    expect(res.status).toBe(400);
    expect(res.body.code).toBe("invalid-svg-file");
  });

  it("returns 413 when the svg is larger than the configured maximum", async () => {
    configureSvgLimits({ maxSize: 10 });
    try {
      const res = await request(app)
        .post("/api/svg/sanitize")
        .set("x-shared-key", "test-key")
        .attach("file", Buffer.from(svg(`<rect width="5" height="5"/>`), "utf8"), {
          filename: "x.svg",
          contentType: "image/svg+xml",
        });

      expect(res.status).toBe(413);
      expect(res.body.code).toBe("svg-too-large");
    } finally {
      configureSvgLimits({ maxSize: DEFAULT_MAX_SIZE });
    }
  });

  it("accepts the multipart body the JVM backend builds", async () => {
    const boundary = `----PenpotBoundary${Date.now()}`;
    const body = backendMultipartBody(boundary, svg(`<script>alert("xss")</script><rect width="5" height="5"/>`));

    const res = await request(app)
      .post("/api/svg/sanitize")
      .set("x-shared-key", "test-key")
      .set("Content-Type", `multipart/form-data; boundary=${boundary}`)
      .send(Buffer.from(body, "utf8"));

    expect(res.status).toBe(200);
    expect(res.headers["content-type"]).toContain("image/svg+xml");
    const out = Buffer.isBuffer(res.body) ? res.body.toString("utf8") : res.text;
    expect(out).toMatch(/^<svg[\s/>]/i);
    expect(out).not.toMatch(/<script/i);
  });

  it("returns the JSON error shape the JVM backend translates", async () => {
    const boundary = `----PenpotBoundary${Date.now()}`;
    const body = backendMultipartBody(boundary, "this is not an svg");

    const res = await request(app)
      .post("/api/svg/sanitize")
      .set("x-shared-key", "test-key")
      .set("Content-Type", `multipart/form-data; boundary=${boundary}`)
      .send(Buffer.from(body, "utf8"));

    expect(res.status).toBe(400);
    expect(res.body).toEqual(expect.objectContaining({ type: "validation", code: "invalid-svg-file" }));
  });
});
