// Engine-agnostic WebGL memory accounting. Passed to `page.addInitScript`,
// so it must stay self-contained (no imports, no outer references).
//
// Patches the WebGL prototypes before the app creates its context and keeps
// running totals of the bytes backing textures, renderbuffers and buffers,
// plus counters for every storage (re)allocation. Sizes are what WebGL was
// asked for; drivers add padding, alignment and their own copies on top.
export function installWebGLMemoryTracker() {
  if (globalThis.__gpuMem) return;

  const protos = [
    globalThis.WebGL2RenderingContext?.prototype,
    globalThis.WebGLRenderingContext?.prototype,
  ].filter(Boolean);
  if (protos.length === 0) return;

  const TEXTURE_2D = 0x0de1;
  const TEXTURE_CUBE_MAP = 0x8513;
  const CUBE_FACE_FIRST = 0x8515;
  const CUBE_FACE_LAST = 0x851a;
  const ELEMENT_ARRAY_BUFFER = 0x8893;

  const SIZED_BPP = {
    0x8229: 1, // R8
    0x822b: 2, // RG8
    0x8051: 3, // RGB8
    0x8058: 4, // RGBA8
    0x8c43: 4, // SRGB8_ALPHA8
    0x8d62: 2, // RGB565
    0x8056: 2, // RGBA4
    0x8057: 2, // RGB5_A1
    0x8059: 4, // RGB10_A2
    0x822d: 2, // R16F
    0x822f: 4, // RG16F
    0x881a: 8, // RGBA16F
    0x822e: 4, // R32F
    0x8230: 8, // RG32F
    0x8814: 16, // RGBA32F
    0x8c3a: 4, // R11F_G11F_B10F
    0x81a5: 2, // DEPTH_COMPONENT16
    0x81a6: 4, // DEPTH_COMPONENT24
    0x8cac: 4, // DEPTH_COMPONENT32F
    0x88f0: 4, // DEPTH24_STENCIL8
    0x8cad: 8, // DEPTH32F_STENCIL8
    0x8d48: 1, // STENCIL_INDEX8
    0x8232: 1, // R8UI
    0x8d7c: 4, // RGBA8UI
  };
  const UNSIZED_CHANNELS = {
    0x1906: 1, // ALPHA
    0x1909: 1, // LUMINANCE
    0x190a: 2, // LUMINANCE_ALPHA
    0x1907: 3, // RGB
    0x1908: 4, // RGBA
    0x1903: 1, // RED
    0x8227: 2, // RG
  };
  const TYPE_BYTES = {
    0x1401: 1, // UNSIGNED_BYTE
    0x1406: 4, // FLOAT
    0x140b: 2, // HALF_FLOAT
    0x8d61: 2, // HALF_FLOAT_OES
  };
  const PACKED_TYPES = new Set([0x8363, 0x8033, 0x8034]); // 565, 4444, 5551

  function bpp(internalformat, format, type) {
    if (SIZED_BPP[internalformat]) return SIZED_BPP[internalformat];
    if (PACKED_TYPES.has(type)) return 2;
    const channels =
      UNSIZED_CHANNELS[internalformat] ?? UNSIZED_CHANNELS[format];
    if (channels) return channels * (TYPE_BYTES[type] ?? 1);
    return 4;
  }

  function sourceSize(src) {
    if (!src) return [0, 0];
    const w =
      src.naturalWidth || src.videoWidth || src.displayWidth || src.width || 0;
    const h =
      src.naturalHeight ||
      src.videoHeight ||
      src.displayHeight ||
      src.height ||
      0;
    return [w, h];
  }

  const counters = {
    textureAllocs: 0,
    renderbufferAllocs: 0,
    bufferAllocs: 0,
    texturesCreated: 0,
    texturesDeleted: 0,
    renderbuffersCreated: 0,
    renderbuffersDeleted: 0,
    buffersCreated: 0,
    buffersDeleted: 0,
    framebuffersCreated: 0,
    framebuffersDeleted: 0,
  };
  const totals = { texture: 0, renderbuffer: 0, buffer: 0 };
  let allocatedBytesTotal = 0;
  // Allocations since the last snapshot, by kind and size: shows which
  // surfaces a step reallocated.
  const allocsBySize = new Map();

  function logAlloc(kind, w, h, bytes) {
    const key = `${kind} ${w}x${h}`;
    const entry = allocsBySize.get(key) ?? { count: 0, bytes: 0 };
    entry.count++;
    entry.bytes += bytes;
    allocsBySize.set(key, entry);
  }
  let contextLosses = 0;

  // texture -> Map("face:level" -> bytes); renderbuffer/buffer -> bytes
  const textureLevels = new Map();
  const objectBytes = { renderbuffer: new Map(), buffer: new Map() };

  const contexts = [];
  const stateByContext = new WeakMap();
  const DEFAULT_VAO = {};

  function stateOf(gl) {
    let s = stateByContext.get(gl);
    if (!s) {
      s = {
        unit: 0,
        textures: new Map(),
        buffers: new Map(),
        renderbuffer: null,
        vao: DEFAULT_VAO,
        vaoElementBuffers: new Map(),
      };
      stateByContext.set(gl, s);
    }
    return s;
  }

  function boundTexture(gl, target) {
    const s = stateOf(gl);
    const bindTarget =
      target >= CUBE_FACE_FIRST && target <= CUBE_FACE_LAST
        ? TEXTURE_CUBE_MAP
        : target;
    return s.textures.get(s.unit)?.get(bindTarget) ?? null;
  }

  function setTextureLevel(gl, target, level, bytes) {
    const tex = boundTexture(gl, target);
    if (!tex) return;
    let levels = textureLevels.get(tex);
    if (!levels) {
      levels = new Map();
      textureLevels.set(tex, levels);
    }
    const face =
      target >= CUBE_FACE_FIRST && target <= CUBE_FACE_LAST
        ? target - CUBE_FACE_FIRST
        : 0;
    const key = `${face}:${level}`;
    totals.texture += bytes - (levels.get(key) ?? 0);
    levels.set(key, bytes);
    allocatedBytesTotal += bytes;
    counters.textureAllocs++;
  }

  function setTextureStorage(gl, target, levelsCount, w, h, d, bytesPerPixel) {
    const faces = target === TEXTURE_CUBE_MAP ? 6 : 1;
    for (let face = 0; face < faces; face++) {
      const faceTarget = faces === 6 ? CUBE_FACE_FIRST + face : target;
      for (let l = 0; l < levelsCount; l++) {
        const lw = Math.max(1, w >> l);
        const lh = Math.max(1, h >> l);
        setTextureLevel(gl, faceTarget, l, lw * lh * d * bytesPerPixel);
      }
    }
    // One storage call is one allocation, however many levels it fills.
    counters.textureAllocs -= faces * levelsCount - 1;
  }

  function setObjectBytes(kind, obj, bytes) {
    if (!obj) return;
    const map = objectBytes[kind];
    totals[kind] += bytes - (map.get(obj) ?? 0);
    map.set(obj, bytes);
    allocatedBytesTotal += bytes;
    counters[`${kind}Allocs`]++;
  }

  function dropObject(kind, obj) {
    const map = objectBytes[kind];
    if (!obj || !map.has(obj)) return;
    totals[kind] -= map.get(obj);
    map.delete(obj);
  }

  function wrap(proto, name, after) {
    const original = proto[name];
    if (typeof original !== "function") return;
    proto[name] = function (...args) {
      const result = original.apply(this, args);
      try {
        after(this, args, result);
      } catch (_) {
        // Accounting must never break rendering.
      }
      return result;
    };
  }

  for (const proto of protos) {
    wrap(proto, "activeTexture", (gl, [unit]) => {
      stateOf(gl).unit = unit;
    });
    wrap(proto, "bindTexture", (gl, [target, tex]) => {
      const s = stateOf(gl);
      if (!s.textures.has(s.unit)) s.textures.set(s.unit, new Map());
      s.textures.get(s.unit).set(target, tex);
    });
    wrap(proto, "texImage2D", (gl, a) => {
      if (a.length >= 8) {
        const bytes = a[3] * a[4] * bpp(a[2], a[6], a[7]);
        setTextureLevel(gl, a[0], a[1], bytes);
        logAlloc("texture", a[3], a[4], bytes);
      } else {
        const [w, h] = sourceSize(a[5]);
        const bytes = w * h * bpp(a[2], a[3], a[4]);
        setTextureLevel(gl, a[0], a[1], bytes);
        logAlloc("texture", w, h, bytes);
      }
    });
    wrap(proto, "texImage3D", (gl, a) => {
      setTextureLevel(
        gl,
        a[0],
        a[1],
        a[3] * a[4] * a[5] * bpp(a[2], a[7], a[8]),
      );
    });
    wrap(proto, "copyTexImage2D", (gl, a) => {
      setTextureLevel(gl, a[0], a[1], a[5] * a[6] * bpp(a[2]));
    });
    wrap(proto, "compressedTexImage2D", (gl, a) => {
      const size = typeof a[6] === "number" ? a[6] : (a[6]?.byteLength ?? 0);
      setTextureLevel(gl, a[0], a[1], size);
    });
    wrap(proto, "texStorage2D", (gl, a) => {
      setTextureStorage(gl, a[0], a[1], a[3], a[4], 1, bpp(a[2]));
      logAlloc("texture", a[3], a[4], a[3] * a[4] * bpp(a[2]));
    });
    wrap(proto, "texStorage3D", (gl, a) => {
      setTextureStorage(gl, a[0], a[1], a[3], a[4], a[5], bpp(a[2]));
    });
    wrap(proto, "createTexture", () => counters.texturesCreated++);
    wrap(proto, "deleteTexture", (_gl, [tex]) => {
      if (!tex) return;
      counters.texturesDeleted++;
      const levels = textureLevels.get(tex);
      if (!levels) return;
      for (const bytes of levels.values()) totals.texture -= bytes;
      textureLevels.delete(tex);
    });

    wrap(proto, "bindRenderbuffer", (gl, [, rb]) => {
      stateOf(gl).renderbuffer = rb;
    });
    wrap(proto, "renderbufferStorage", (gl, [, fmt, w, h]) => {
      const bytes = w * h * bpp(fmt);
      setObjectBytes("renderbuffer", stateOf(gl).renderbuffer, bytes);
      logAlloc("renderbuffer", w, h, bytes);
    });
    wrap(
      proto,
      "renderbufferStorageMultisample",
      (gl, [, samples, fmt, w, h]) => {
        const bytes = w * h * bpp(fmt) * Math.max(1, samples);
        setObjectBytes("renderbuffer", stateOf(gl).renderbuffer, bytes);
        logAlloc("renderbuffer", w, h, bytes);
      },
    );
    wrap(proto, "createRenderbuffer", () => counters.renderbuffersCreated++);
    wrap(proto, "deleteRenderbuffer", (_gl, [rb]) => {
      if (!rb) return;
      counters.renderbuffersDeleted++;
      dropObject("renderbuffer", rb);
    });

    const bindBuffer = (gl, target, buf) => {
      const s = stateOf(gl);
      s.buffers.set(target, buf);
      if (target === ELEMENT_ARRAY_BUFFER) s.vaoElementBuffers.set(s.vao, buf);
    };
    wrap(proto, "bindBuffer", (gl, [target, buf]) =>
      bindBuffer(gl, target, buf),
    );
    wrap(proto, "bindBufferBase", (gl, [target, , buf]) =>
      bindBuffer(gl, target, buf),
    );
    wrap(proto, "bindBufferRange", (gl, [target, , buf]) =>
      bindBuffer(gl, target, buf),
    );
    const bindVertexArray = (gl, [vao]) => {
      const s = stateOf(gl);
      s.vao = vao ?? DEFAULT_VAO;
      s.buffers.set(
        ELEMENT_ARRAY_BUFFER,
        s.vaoElementBuffers.get(s.vao) ?? null,
      );
    };
    wrap(proto, "bindVertexArray", bindVertexArray);
    wrap(proto, "bindVertexArrayOES", bindVertexArray);
    wrap(proto, "bufferData", (gl, [target, data, , srcOffset, length]) => {
      let bytes;
      if (typeof data === "number") {
        bytes = data;
      } else if (data && ArrayBuffer.isView(data)) {
        const bpe = data.BYTES_PER_ELEMENT ?? 1;
        const elements = data.byteLength / bpe;
        bytes = length ? length * bpe : (elements - (srcOffset ?? 0)) * bpe;
      } else {
        bytes = data?.byteLength ?? 0;
      }
      setObjectBytes("buffer", stateOf(gl).buffers.get(target), bytes);
    });
    wrap(proto, "createBuffer", () => counters.buffersCreated++);
    wrap(proto, "deleteBuffer", (_gl, [buf]) => {
      if (!buf) return;
      counters.buffersDeleted++;
      dropObject("buffer", buf);
    });

    wrap(proto, "createFramebuffer", () => counters.framebuffersCreated++);
    wrap(proto, "deleteFramebuffer", (_gl, [fb]) => {
      if (fb) counters.framebuffersDeleted++;
    });
  }

  function trackContext(canvas, ctx) {
    const isGL = protos.some((p) => Object.getPrototypeOf(ctx) === p);
    if (!isGL || contexts.some((c) => c.ctx === ctx)) return;
    contexts.push({ canvas, ctx });
    canvas.addEventListener?.("webglcontextlost", () => contextLosses++);
  }

  for (const Canvas of [
    globalThis.HTMLCanvasElement,
    globalThis.OffscreenCanvas,
  ]) {
    if (!Canvas) continue;
    const original = Canvas.prototype.getContext;
    Canvas.prototype.getContext = function (...args) {
      const ctx = original.apply(this, args);
      if (ctx) trackContext(this, ctx);
      return ctx;
    };
  }

  function drawingBuffers() {
    let pixels = 0;
    let bytes = 0;
    let preserve = false;
    for (const { ctx } of contexts) {
      if (ctx.isContextLost()) continue;
      const attrs = ctx.getContextAttributes() ?? {};
      const px = ctx.drawingBufferWidth * ctx.drawingBufferHeight;
      // Estimate: front + back colour buffers, optional depth/stencil.
      const perPixel = 8 + (attrs.depth || attrs.stencil ? 4 : 0);
      pixels += px;
      bytes += px * perPixel;
      preserve ||= !!attrs.preserveDrawingBuffer;
    }
    return { pixels, bytesEstimate: bytes, preserveDrawingBuffer: preserve };
  }

  // Keep a reference to each instance's linear memory; exports stay untouched.
  const wasmMemories = [];
  for (const name of ["instantiate", "instantiateStreaming"]) {
    const original = WebAssembly[name];
    if (typeof original !== "function") continue;
    WebAssembly[name] = function (...args) {
      return original.apply(this, args).then((result) => {
        const memory = (result.instance ?? result)?.exports?.memory;
        if (memory instanceof WebAssembly.Memory) wasmMemories.push(memory);
        return result;
      });
    };
  }

  function skiaCache() {
    const mod = globalThis.app?.common?.render_wasm?.wasm?.internal_module;
    if (typeof mod?._resource_cache_bytes !== "function") return null;
    return {
      bytes: mod._resource_cache_bytes(),
      purgeableBytes: mod._resource_cache_purgeable_bytes(),
    };
  }

  function wasmHeapBytes() {
    if (wasmMemories.length === 0) return null;
    return wasmMemories.reduce((acc, m) => acc + m.buffer.byteLength, 0);
  }

  function rendererInfo() {
    for (const { ctx } of contexts) {
      if (ctx.isContextLost()) continue;
      const ext = ctx.getExtension("WEBGL_debug_renderer_info");
      return {
        vendor: ctx.getParameter(ext ? ext.UNMASKED_VENDOR_WEBGL : ctx.VENDOR),
        renderer: ctx.getParameter(
          ext ? ext.UNMASKED_RENDERER_WEBGL : ctx.RENDERER,
        ),
        version: ctx.getParameter(ctx.VERSION),
      };
    }
    return null;
  }

  globalThis.__gpuMem = {
    rendererInfo,
    snapshot() {
      const drawingBuffer = drawingBuffers();
      const bySize = [...allocsBySize.entries()]
        .map(([key, v]) => ({ key, ...v }))
        .sort((a, b) => b.bytes - a.bytes)
        .slice(0, 12);
      allocsBySize.clear();
      return {
        textures: { count: textureLevels.size, bytes: totals.texture },
        renderbuffers: {
          count: objectBytes.renderbuffer.size,
          bytes: totals.renderbuffer,
        },
        buffers: { count: objectBytes.buffer.size, bytes: totals.buffer },
        totalBytes: totals.texture + totals.renderbuffer + totals.buffer,
        drawingBuffer,
        allocs: {
          ...counters,
          total:
            counters.textureAllocs +
            counters.renderbufferAllocs +
            counters.bufferAllocs,
        },
        allocatedBytesTotal,
        allocsBySize: bySize,
        contexts: contexts.length,
        contextLosses,
        wasmHeapBytes: wasmHeapBytes(),
        skiaCache: skiaCache(),
        jsHeapBytes: performance.memory?.usedJSHeapSize ?? null,
      };
    },
  };
}
