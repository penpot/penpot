let Module = null;

let scale = 1;
let offsetX = 0;
let offsetY = 0;

let isPanning = false;
let lastX = 0;
let lastY = 0;

export function init(moduleInstance) {
  Module = moduleInstance;
}

export function assignCanvas(canvas) {
  const glModule = Module.GL;
  const context = canvas.getContext("webgl2", {
    antialias: true,
    depth: true,
    alpha: false,
    stencil: true,
    preserveDrawingBuffer: true,
  });

  const handle = glModule.registerContext(context, { majorVersion: 2 });
  glModule.makeContextCurrent(handle);
  context.getExtension("WEBGL_debug_renderer_info");

  Module._init(canvas.width, canvas.height);
  Module._set_render_options(0, 1);
}

export function hexToU32ARGB(hex, opacity = 1) {
  const rgb = parseInt(hex.slice(1), 16);
  const a = Math.floor(opacity * 0xff);
  const argb = (a << 24) | rgb;
  return argb >>> 0;
}

export function getRandomInt(min, max) {
  return Math.floor(Math.random() * (max - min)) + min;
}

export function getRandomColor() {
  const r = getRandomInt(0, 256).toString(16).padStart(2, "0");
  const g = getRandomInt(0, 256).toString(16).padStart(2, "0");
  const b = getRandomInt(0, 256).toString(16).padStart(2, "0");
  return `#${r}${g}${b}`;
}

export function getRandomFloat(min, max) {
  return Math.random() * (max - min) + min;
}

function getU32(id) {
  const hex = id.replace(/-/g, "");
  const buffer = new Uint32Array(4);
  for (let i = 0; i < 4; i++) {
    buffer[i] = parseInt(hex.slice(i * 8, (i + 1) * 8), 16);
  }
  return buffer;
}

function heapU32SetUUID(id, heap, offset) {
  const buffer = getU32(id);
  heap.set(buffer, offset);
  return buffer;
}

function ptr8ToPtr32(ptr8) {
  return ptr8 >>> 2;
}

export function allocBytes(size) {
  return Module._alloc_bytes(size);
}

export function getHeapU32() {
  return Module.HEAPU32;
}

export function clearShapeFills() {
  Module._clear_shape_fills();
}

export function addShapeSolidFill(argb) {
  const ptr = allocBytes(160);
  const heap = getHeapU32();
  const dv = new DataView(heap.buffer);
  dv.setUint8(ptr, 0x00, true);
  dv.setUint32(ptr + 4, argb, true);
  Module._add_shape_fill();
}

export function addShapeSolidStrokeFill(argb) {
  const ptr = allocBytes(160);
  const heap = getHeapU32();
  const dv = new DataView(heap.buffer);
  dv.setUint8(ptr, 0x00, true);
  dv.setUint32(ptr + 4, argb, true);
  Module._add_shape_stroke_fill();
}

function serializePathAttrs(svgAttrs) {
  return Object.entries(svgAttrs).reduce((acc, [key, value]) => {
    return acc + key + "\0" + value + "\0";
  }, "");
}

export function draw_star(x, y, width, height) {
  const len = 11; // 1 MOVE + 9 LINE + 1 CLOSE
  const ptr = allocBytes(len * 28);
  const heap = getHeapU32();
  const dv = new DataView(heap.buffer);

  const cx = x + width / 2;
  const cy = y + height / 2;
  const outerRadius = Math.min(width, height) / 2;
  const innerRadius = outerRadius * 0.4;

  const star = [];
  for (let i = 0; i < 10; i++) {
    const angle = (Math.PI / 5) * i - Math.PI / 2;
    const r = i % 2 === 0 ? outerRadius : innerRadius;
    const px = cx + r * Math.cos(angle);
    const py = cy + r * Math.sin(angle);
    star.push([px, py]);
  }

  let offset = 0;

  // MOVE to first point
  dv.setUint16(ptr + offset + 0, 1, true); // MOVE
  dv.setFloat32(ptr + offset + 20, star[0][0], true);
  dv.setFloat32(ptr + offset + 24, star[0][1], true);
  offset += 28;

  // LINE to remaining points
  for (let i = 1; i < star.length; i++) {
    dv.setUint16(ptr + offset + 0, 2, true); // LINE
    dv.setFloat32(ptr + offset + 20, star[i][0], true);
    dv.setFloat32(ptr + offset + 24, star[i][1], true);
    offset += 28;
  }

  // CLOSE the path
  dv.setUint16(ptr + offset + 0, 4, true); // CLOSE

  Module._set_shape_path_content();
}

export function setShapeChildren(shapeIds) {
  const offset = allocBytes(shapeIds.length * 16);
  const heap = getHeapU32();
  let currentOffset = offset;
  for (const id of shapeIds) {
    heapU32SetUUID(id, heap, ptr8ToPtr32(currentOffset));
    currentOffset += 16;
  }
  return Module._set_children();
}

export function useShape(id) {
  const buffer = getU32(id);
  Module._use_shape(...buffer);
}

export function set_parent(id) {
  const buffer = getU32(id);
  Module._set_parent(...buffer);
}

function debounce(fn, delay) {
  let timeout;
  return (...args) => {
    clearTimeout(timeout);
    timeout = setTimeout(() => fn(...args), delay);
  };
}

const debouncedRender = debounce(() => {
  Module._render(Date.now());
}, 100);

export function setupInteraction(canvas) {
  canvas.addEventListener("wheel", (e) => {
    e.preventDefault();
    const zoomFactor = e.deltaY < 0 ? 1.1 : 0.9;
    scale *= zoomFactor;
    const mouseX = e.offsetX;
    const mouseY = e.offsetY;
    offsetX -= (mouseX - offsetX) * (zoomFactor - 1);
    offsetY -= (mouseY - offsetY) * (zoomFactor - 1);
    Module._set_view(scale, offsetX, offsetY);
    Module._render_from_cache();
    debouncedRender();
  });

  canvas.addEventListener("mousedown", (e) => {
    isPanning = true;
    lastX = e.offsetX;
    lastY = e.offsetY;
  });

  canvas.addEventListener("mousemove", (e) => {
    if (isPanning) {
      const dx = e.offsetX - lastX;
      const dy = e.offsetY - lastY;
      offsetX += dx;
      offsetY += dy;
      lastX = e.offsetX;
      lastY = e.offsetY;
      Module._set_view(scale, offsetX, offsetY);
      Module._render_from_cache();
      debouncedRender();
    }
  });

  canvas.addEventListener("mouseup", () => {
    isPanning = false;
  });
  canvas.addEventListener("mouseout", () => {
    isPanning = false;
  });
}

export function addTextShape(x, y, fontSize, text) {
  const numLeaves = 1; // Single text leaf for simplicity
  const paragraphAttrSize = 48;
  // const leafAttrSize = 56;
  const singleFillSize = 160;
  const textBuffer = new TextEncoder().encode(text);
  const textSize = textBuffer.byteLength;

  const leafSize = 1340; // leaf attrs + 8 fills

  // Calculate fills
  const fills = [
    {
      type: "solid",
      color: getRandomColor(),
      opacity: getRandomFloat(0.5, 1.0),
    },
  ];
  const totalSize = paragraphAttrSize + leafSize + textSize;

  // Allocate buffer
  const bufferPtr = allocBytes(totalSize);
  const heap = new Uint8Array(Module.HEAPU8.buffer, bufferPtr, totalSize);
  const dview = new DataView(heap.buffer, bufferPtr, totalSize);

  // Set number of leaves
  dview.setUint32(0, numLeaves, true);

  // Serialize paragraph attributes
  dview.setUint8(4, 1); // text-align: left
  dview.setUint8(5, 0); // text-direction: LTR
  dview.setUint8(6, 0); // text-decoration: none
  dview.setUint8(7, 0); // text-transform: none
  dview.setFloat32(8, 1.2, true); // line-height
  dview.setFloat32(12, 0, true); // letter-spacing
  dview.setUint32(16, 0, true); // typography-ref-file (UUID part 1)
  dview.setUint32(20, 0, true); // typography-ref-file (UUID part 2)
  dview.setUint32(24, 0, true); // typography-ref-file (UUID part 3)
  dview.setInt32(28, 0, true); // typography-ref-file (UUID part 4)
  dview.setUint32(32, 0, true); // typography-ref-id (UUID part 1)
  dview.setUint32(36, 0, true); // typography-ref-id (UUID part 2)
  dview.setUint32(40, 0, true); // typography-ref-id (UUID part 3)
  dview.setInt32(44, 0, true); // typography-ref-id (UUID part 4)

  // Serialize leaf attributes
  const leafOffset = paragraphAttrSize;
  dview.setUint8(leafOffset, 0); // font-style: normal
  dview.setFloat32(leafOffset + 4, fontSize, true); // font-size
  dview.setFloat32(leafOffset + 8, 0, true); // letter-spacing
  dview.setUint32(leafOffset + 12, 400, true); // font-weight: normal
  dview.setUint32(leafOffset + 16, 0, true); // font-id (UUID part 1)
  dview.setUint32(leafOffset + 20, 0, true); // font-id (UUID part 2)
  dview.setUint32(leafOffset + 24, 0, true); // font-id (UUID part 3)
  dview.setInt32(leafOffset + 28, 0, true); // font-id (UUID part 4)
  dview.setInt32(leafOffset + 32, 0, true); // font-family hash
  dview.setUint32(leafOffset + 36, 0, true); // font-variant-id (UUID part 1)
  dview.setUint32(leafOffset + 40, 0, true); // font-variant-id (UUID part 2)
  dview.setUint32(leafOffset + 44, 0, true); // font-variant-id (UUID part 3)
  dview.setInt32(leafOffset + 48, 0, true); // font-variant-id (UUID part 4)
  dview.setInt32(leafOffset + 52, textSize, true); // text-length
  dview.setInt32(leafOffset + 56, fills.length, true); // total fills count

  // Serialize fills
  let fillOffset = leafOffset + 60;
  fills.forEach((fill) => {
    if (fill.type === "solid") {
      const argb = hexToU32ARGB(fill.color, fill.opacity);
      dview.setUint8(fillOffset, 0x00, true); // Fill type: solid
      dview.setUint32(fillOffset + 4, argb, true);
      fillOffset += singleFillSize; // Move to the next fill
    }
  });

  // Add text content
  const textOffset = leafSize;
  heap.set(textBuffer, textOffset);

  // Call the WebAssembly function
  Module._set_shape_text_content();
}
