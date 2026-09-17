// FFI layouts are defined by render-wasm/src/wasm/{text,fills,strokes}.rs.
// Keep this serializer covered by ABI regression tests when those layouts change.
export const ROOT = Object.freeze([0, 0, 0, 0]);
export const BACKGROUND = 0xfffabada;

export function validateParameters(params, allowed) {
  for (const key of Object.keys(params)) {
    if (!allowed.includes(key))
      throw new Error(`Unsupported parameter: ${key}`);
  }
}

export function validateCount(value) {
  if (typeof value !== "number" && typeof value !== "string") {
    throw new Error("Shape count must be a positive integer (maximum 100000)");
  }
  const count = Number(value);
  if (!Number.isSafeInteger(count) || count < 1 || count > 100000) {
    throw new Error("Shape count must be a positive integer (maximum 100000)");
  }
  return count;
}

export function randomSource(seed) {
  if (!Number.isInteger(seed) || seed < 0 || seed > 0xffffffff) {
    throw new Error("Scene seed must be an unsigned 32-bit integer");
  }
  let state = seed >>> 0;
  const random = () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = Math.imul(state ^ (state >>> 15), state | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
  return {
    float: (min, max) => min + random() * (max - min),
    int: (min, max) => Math.floor(min + random() * (max - min)),
    id: () => [
      Math.floor(random() * 4294967296),
      ((Math.floor(random() * 4294967296) & 0xffff0fff) | 0x4000) >>> 0,
      ((Math.floor(random() * 4294967296) & 0x3fffffff) | 0x80000000) >>> 0,
      Math.floor(random() * 4294967296),
    ],
  };
}

export function argb(rgb, opacity = 1) {
  return ((Math.floor(opacity * 255) << 24) | rgb) >>> 0;
}

export function solidFill(color) {
  const bytes = new Uint8Array(160);
  new DataView(bytes.buffer).setUint32(4, color, true);
  return bytes;
}

export function serializeText(text, fontSize, color) {
  const encoded = new TextEncoder().encode(text);
  // RawParagraphData=16; RawTextSpan=64+8*160=1344.
  const bytes = new Uint8Array(16 + 1344 + encoded.length);
  const view = new DataView(bytes.buffer);
  view.setUint32(0, 1, true);
  view.setFloat32(8, 1.2, true);
  const span = 16;
  view.setFloat32(span + 4, fontSize, true);
  view.setFloat32(span + 8, 1.2, true);
  view.setInt32(span + 16, 400, true);
  view.setUint32(span + 56, encoded.length, true);
  view.setUint32(span + 60, 1, true);
  bytes.set(solidFill(color), span + 64);
  bytes.set(encoded, span + 1344);
  return bytes;
}

export function serializePath(points, close = false) {
  const bytes = new Uint8Array((points.length + Number(close)) * 28);
  const view = new DataView(bytes.buffer);
  for (let i = 0; i < points.length; i++) {
    view.setUint16(i * 28, i === 0 ? 1 : 2, true);
    view.setFloat32(i * 28 + 20, points[i][0], true);
    view.setFloat32(i * 28 + 24, points[i][1], true);
  }
  if (close) view.setUint16(points.length * 28, 4, true);
  return bytes;
}

export function serializeChildren(children) {
  const bytes = new Uint8Array(children.length * 16);
  const view = new DataView(bytes.buffer);
  children.forEach((id, i) =>
    id.forEach((part, j) => view.setUint32(i * 16 + j * 4, part, true)),
  );
  return bytes;
}

export function finishScene(shapes) {
  for (const shape of shapes) {
    if (shape.children) shape.childrenBytes = serializeChildren(shape.children);
  }
  const children = shapes
    .filter((shape) => shape.parent.every((part) => part === 0))
    .map((shape) => shape.id);
  return { shapes, children, childrenBytes: serializeChildren(children) };
}

export function rectangle(id, parent, bounds, color) {
  return { id, parent, type: 3, bounds, fills: [solidFill(color)] };
}

export function stroke(width, color) {
  return { args: [width, 0, 0, 0, -1, -1], fill: solidFill(color) };
}

export function uploadBytes(module, bytes, setter) {
  const pointer = module._alloc_bytes(bytes.byteLength);
  // Allocation can grow the WASM heap, so obtain HEAPU8 after allocating.
  module.HEAPU8.set(bytes, pointer);
  module[setter]();
}

export function uploadScene(module, scene) {
  module._init_shapes_pool(scene.shapes.length + 1);
  module._begin_loading();
  try {
    module._use_shape(...ROOT);
    for (const shape of scene.shapes) {
      module._use_shape(...shape.id);
      module._set_parent(...shape.parent);
      module._set_shape_type(shape.type);
      module._set_shape_selrect(...shape.bounds);
      if (shape.path)
        uploadBytes(module, shape.path, "_set_shape_path_content");
      for (const fill of shape.fills ?? [])
        uploadBytes(module, fill, "_add_shape_fill");
      for (const item of shape.strokes ?? []) {
        module._add_shape_center_stroke(...item.args);
        uploadBytes(module, item.fill, "_add_shape_stroke_fill");
      }
      if (shape.corners) module._set_shape_corners(...shape.corners);
      if (shape.blur) module._set_shape_blur(...shape.blur);
      for (const shadow of shape.shadows ?? [])
        module._add_shape_shadow(...shadow);
      if (shape.masked) module._set_shape_masked_group(true);
      if (shape.clip !== undefined) module._set_shape_clip_content(shape.clip);
      if (shape.childrenBytes)
        uploadBytes(module, shape.childrenBytes, "_set_children");
      if (shape.textBytes)
        uploadBytes(module, shape.textBytes, "_set_shape_text_content");
    }
    module._use_shape(...ROOT);
    uploadBytes(module, scene.childrenBytes, "_set_children");
  } finally {
    module._end_loading();
  }
  // Like the product bridge, compute text layouts only after ending loading.
  for (const shape of scene.shapes) {
    if (shape.textBytes) module._update_shape_text_layout_for(...shape.id);
  }
}

export function defaultCase(params, { anchor, panDistance, frameCount = 20 }) {
  return {
    id: "default",
    params,
    initialView: { scale: 1, x: 0, y: 0 },
    interactions: {
      pan: {
        settleMs: 100,
        frames: Array.from({ length: frameCount }, (_, i) => ({
          scale: 1,
          x: (panDistance[0] * (i + 1)) / frameCount,
          y: (panDistance[1] * (i + 1)) / frameCount,
        })),
      },
      zoom: {
        settleMs: 100,
        anchor,
        frames: Array.from({ length: frameCount }, (_, i) => {
          const scale = 1 + (i + 1) / frameCount;
          return {
            scale,
            x: anchor[0] / scale - anchor[0],
            y: anchor[1] / scale - anchor[1],
          };
        }),
      },
    },
  };
}

export function randomShapes(kind, params, seed) {
  validateParameters(params, [
    "count",
    "width",
    "height",
    "minSize",
    "maxSize",
    ...(kind === "shadows" ? ["shadows"] : []),
  ]);
  const count = validateCount(params.count);
  for (const name of ["width", "height", "minSize", "maxSize"]) {
    if (!Number.isFinite(params[name]) || params[name] <= 0)
      throw new Error(`Invalid ${name}`);
  }
  if (params.minSize >= params.maxSize) throw new Error("Invalid size range");
  if (
    kind === "shadows" &&
    (!Array.isArray(params.shadows) ||
      !params.shadows.every(
        (shadow) =>
          Array.isArray(shadow) &&
          shadow.length === 7 &&
          Number.isInteger(shadow[0]) &&
          shadow[0] >= 0 &&
          shadow[0] <= 0xffffffff &&
          shadow.slice(1, 5).every(Number.isFinite) &&
          shadow[1] >= 0 &&
          [0, 1].includes(shadow[5]) &&
          typeof shadow[6] === "boolean",
      ))
  ) {
    throw new Error("Invalid shadows");
  }
  const rng = randomSource(seed);
  const shapes = [];
  for (let i = 0; i < count; i++) {
    const id = rng.id();
    const x = rng.int(0, params.width);
    const y = rng.int(0, params.height);
    const width = rng.int(params.minSize, params.maxSize);
    const height = rng.int(params.minSize, params.maxSize);
    const color = rng.int(0, 0x1000000);
    const shape = rectangle(
      id,
      ROOT,
      [x, y, x + width, y + height],
      argb(color, rng.float(0.1, 1)),
    );
    shape.strokes = [stroke(10, argb(color, rng.float(0.1, 1)))];
    if (kind === "paths") {
      shape.type = 4;
      const radius = Math.min(width, height) / 2;
      const points = Array.from({ length: 10 }, (_, point) => {
        const angle = (Math.PI / 5) * point - Math.PI / 2;
        const r = point % 2 === 0 ? radius : radius * 0.4;
        return [
          x + width / 2 + r * Math.cos(angle),
          y + height / 2 + r * Math.sin(angle),
        ];
      });
      shape.path = serializePath(points, true);
    } else if (kind === "plus") {
      shape.type = 4;
      shape.fills = [];
      // Stroke extents account for line thickness. Keep the original zero-area selrect.
      const horizontal = rng.float(0, 1) < 0.5;
      const end = horizontal ? [x + width, y] : [x, y + width];
      shape.bounds = [x, y, ...end];
      shape.path = serializePath([[x, y], end]);
    } else if (kind === "shadows") {
      shape.shadows = params.shadows;
    }
    shapes.push(shape);
  }
  return finishScene(shapes);
}
