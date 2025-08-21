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
  const a = Math.floor(opacity * 0xFF);
  const argb = (a << 24) | rgb;
  return argb >>> 0;
}

export function getRandomInt(min, max) {
  return Math.floor(Math.random() * (max - min)) + min;
}

export function getRandomColor() {
  const r = getRandomInt(0, 256).toString(16).padStart(2, '0');
  const g = getRandomInt(0, 256).toString(16).padStart(2, '0');
  const b = getRandomInt(0, 256).toString(16).padStart(2, '0');
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
    return acc + key + '\0' + value + '\0';
  }, '');
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
    const angle = Math.PI / 5 * i - Math.PI / 2;
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

  const str = serializePathAttrs({
    "fill": "none",
    "stroke-linecap": "round",
    "stroke-linejoin": "round",
  });
  const size = str.length;
  offset = allocBytes(size);
  Module.stringToUTF8(str, offset, size);
  Module._set_shape_path_attrs(3);
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

export function render() {
  console.log('render')
  Module._set_view(1, 0, 0);
  Module._render_from_cache();
  debouncedRender();
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

  canvas.addEventListener("mouseup", () => { isPanning = false; });
  canvas.addEventListener("mouseout", () => { isPanning = false; });
}

const TextAlign = {
  'left': 0,
  'center': 1,
  'right': 2,
  'justify': 3,
}

function getTextAlign(textAlign) {
  if (textAlign in TextAlign) {
    return TextAlign[textAlign];
  }
  return 0;
}

function getTextDirection(textDirection) {
  switch (textDirection) {
    default:
    case 'LTR': return 0;
    case 'RTL': return 1;
  }
}

function getTextDecoration(textDecoration) {
  switch (textDecoration) {
    default:
    case 'none': return 0;
    case 'underline': return 1;
    case 'line-through': return 2;
    case 'overline': return 3;
  }
}

function getTextTransform(textTransform) {
  switch (textTransform) {
    default:
      case 'none': return 0;
      case 'uppercase': return 1;
      case 'lowercase': return 2;
      case 'capitalize': return 3;
  }
}

function getFontStyle(fontStyle) {
  switch (fontStyle) {
    default:
    case 'normal':
    case 'oblique':
    case 'italic':
      return 0;
  }
}

const PARAGRAPH_ATTR_SIZE = 48;
const LEAF_ATTR_SIZE = 60;
const FILL_SIZE = 160;

function setParagraphData(dview, { numLeaves, textAlign, textDirection, textDecoration, textTransform, lineHeight, letterSpacing }) {
  // Set number of leaves
  dview.setUint32(0, numLeaves, true);

  // Serialize paragraph attributes
  dview.setUint8(4, textAlign, true); // text-align: left
  dview.setUint8(5, textDirection, true); // text-direction: LTR
  dview.setUint8(6, textDecoration, true); // text-decoration: none
  dview.setUint8(7, textTransform, true); // text-transform: none
  dview.setFloat32(8, lineHeight, true); // line-height
  dview.setFloat32(12, letterSpacing, true); // letter-spacing
  dview.setUint32(16, 0, true); // typography-ref-file (UUID part 1)
  dview.setUint32(20, 0, true); // typography-ref-file (UUID part 2)
  dview.setUint32(24, 0, true); // typography-ref-file (UUID part 3)
  dview.setUint32(28, 0, true); // typography-ref-file (UUID part 4)
  dview.setUint32(32, 0, true); // typography-ref-id (UUID part 1)
  dview.setUint32(36, 0, true); // typography-ref-id (UUID part 2)
  dview.setUint32(40, 0, true); // typography-ref-id (UUID part 3)
  dview.setUint32(44, 0, true); // typography-ref-id (UUID part 4)
}

function setLeafData(dview, leafOffset, {
  fontStyle,
  fontSize,
  fontWeight,
  letterSpacing,
  textSize,
  totalFills
}) {
  // Serialize leaf attributes
  dview.setUint8(leafOffset + 0, fontStyle, true); // font-style: normal
  dview.setUint8(leafOffset + 1, 0, true); // text-decoration: none
  dview.setUint8(leafOffset + 2, 0, true); // text-transform: none
  dview.setUint8(leafOffset + 3, 0, true); // text-direction: ltr
  dview.setFloat32(leafOffset + 4, fontSize, true); // font-size
  dview.setFloat32(leafOffset + 8, letterSpacing, true); // letter-spacing
  dview.setInt32(leafOffset + 12, fontWeight, true); // font-weight: normal
  dview.setUint32(leafOffset + 16, 0, true); // font-id (UUID part 1)
  dview.setUint32(leafOffset + 20, 0, true); // font-id (UUID part 2)
  dview.setUint32(leafOffset + 24, 0, true); // font-id (UUID part 3)
  dview.setUint32(leafOffset + 28, 0, true); // font-id (UUID part 4)
  dview.setUint32(leafOffset + 32, 0, true); // font-family hash
  dview.setUint32(leafOffset + 36, 0, true); // font-variant-id (UUID part 1)
  dview.setUint32(leafOffset + 40, 0, true); // font-variant-id (UUID part 2)
  dview.setUint32(leafOffset + 44, 0, true); // font-variant-id (UUID part 3)
  dview.setUint32(leafOffset + 48, 0, true); // font-variant-id (UUID part 4)
  dview.setUint32(leafOffset + 52, textSize, true); // text-length
  dview.setUint32(leafOffset + 56, totalFills, true); // total fills count
}

export function updateTextShape(fontSize, root) {
  // Calculate fills
  const fills = [
    {
      type: "solid",
      color: "#ff00ff",
      opacity: 1.0,
    },
  ];

  const totalFills = fills.length;
  const totalFillsSize = totalFills * FILL_SIZE;

  const paragraphs = root.children;
  console.log("paragraphs", paragraphs.length);

  Module._clear_shape_text();
  for (const paragraph of paragraphs) {
    let totalSize = PARAGRAPH_ATTR_SIZE;

    const leaves = paragraph.children;
    const numLeaves = leaves.length;
    console.log("leaves", numLeaves);

    for (const leaf of leaves) {
      const text = leaf.textContent;
      const textBuffer = new TextEncoder().encode(text);
      const textSize = textBuffer.byteLength;
      console.log("text", text, textSize);
      totalSize += LEAF_ATTR_SIZE + totalFillsSize;
    }

    totalSize += paragraph.textContent.length;

    console.log("Total Size", totalSize);
    // Allocate buffer
    const bufferPtr = allocBytes(totalSize);
    const heap = new Uint8Array(Module.HEAPU8.buffer, bufferPtr, totalSize);
    const dview = new DataView(heap.buffer, bufferPtr, totalSize);

    const textAlign = getTextAlign(
      paragraph.style.getPropertyValue("text-align"),
    );
    console.log("text-align", textAlign);
    const textDirection = getTextDirection(
      paragraph.style.getPropertyValue("text-direction"),
    );
    console.log("text-direction", textDirection);
    const textDecoration = getTextDecoration(
      paragraph.style.getPropertyValue("text-decoration"),
    );
    console.log("text-decoration", textDecoration);
    const textTransform = getTextTransform(
      paragraph.style.getPropertyValue("text-transform"),
    );
    console.log("text-transform", textTransform);
    const lineHeight = parseFloat(
      paragraph.style.getPropertyValue("line-height"),
    );
    console.log("line-height", lineHeight);
    const letterSpacing = parseFloat(
      paragraph.style.getPropertyValue("letter-spacing"),
    );
    console.log("letter-spacing", letterSpacing);

    /*
    num_leaves: u32,
    text_align: u8,
    text_direction: u8,
    text_decoration: u8,
    text_transform: u8,
    line_height: f32,
    letter_spacing: f32,
    typography_ref_file: [u32; 4],
    typography_ref_id: [u32; 4],
    */

    setParagraphData(dview, {
      numLeaves,
      textAlign,
      textDecoration,
      textTransform,
      textDirection,
      lineHeight,
      letterSpacing
    })
    let leafOffset = PARAGRAPH_ATTR_SIZE;
    for (const leaf of leaves) {
      console.log(
        "leafOffset",
        leafOffset,
        PARAGRAPH_ATTR_SIZE,
        LEAF_ATTR_SIZE,
        FILL_SIZE,
        totalFills,
        totalFillsSize,
      );
      const fontStyle = getFontStyle(leaf.style.getPropertyValue("font-style"));
      const fontSize = parseFloat(leaf.style.getPropertyValue("font-size"));
      const letterSpacing = parseFloat(leaf.style.getPropertyValue("letter-spacing"))
      console.log("font-size", fontSize, "letter-spacing", letterSpacing);
      const fontWeight = parseInt(
        leaf.style.getPropertyValue("font-weight"),
        10,
      );
      console.log("font-weight", fontWeight);

      const text = leaf.textContent;
      const textBuffer = new TextEncoder().encode(text);
      const textSize = textBuffer.byteLength;

      setLeafData(dview, leafOffset, {
        fontStyle,
        textDecoration: 0,
        textTransform: 0,
        textDirection: 0,
        fontSize,
        fontWeight,
        letterSpacing,
        textSize,
        totalFills
      })

      // Serialize fills
      let fillOffset = leafOffset + LEAF_ATTR_SIZE;
      fills.forEach((fill) => {
        if (fill.type === "solid") {
          const argb = hexToU32ARGB(fill.color, fill.opacity);
          dview.setUint8(fillOffset + 0, 0x00, true); // Fill type: solid
          dview.setUint32(fillOffset + 4, argb, true);
          fillOffset += FILL_SIZE; // Move to the next fill
        }
      });
      leafOffset += LEAF_ATTR_SIZE + totalFillsSize;
    }

    const text = paragraph.textContent;
    const textBuffer = new TextEncoder().encode(text);

    // Add text content
    const textOffset = leafOffset;
    console.log('textOffset', textOffset);
    heap.set(textBuffer, textOffset);

    Module._set_shape_text_content();
  }
}

export function addTextShape(fontSize, text) {
  const numLeaves = 1; // Single text leaf for simplicity
  const textBuffer = new TextEncoder().encode(text);
  const textSize = textBuffer.byteLength;

  // Calculate fills
  const fills = [
    {
      type: "solid",
      color: "#ff00ff",
      opacity: 1.0,
    },
  ];
  const totalFills = fills.length;
  const totalFillsSize = totalFills * FILL_SIZE;

  // Calculate metadata and total buffer size
  const metadataSize = PARAGRAPH_ATTR_SIZE + LEAF_ATTR_SIZE + totalFillsSize;
  const totalSize = metadataSize + textSize;

  // Allocate buffer
  const bufferPtr = allocBytes(totalSize);
  const heap = new Uint8Array(Module.HEAPU8.buffer, bufferPtr, totalSize);
  const dview = new DataView(heap.buffer, bufferPtr, totalSize);

  setParagraphData(dview, {
    numLeaves,
    textAlign: 0,
    textDecoration: 0,
    textTransform: 0,
    textDirection: 0,
    lineHeight: 1.2,
    letterSpacing: 0,
  });

  // Serialize leaf attributes
  const leafOffset = PARAGRAPH_ATTR_SIZE;
  setLeafData(dview, leafOffset, {
    fontSize,
    fontWeight: 400,
    textDecoration: 0,
    textDirection: 0,
    textTransform: 0,
    letterSpacing: 0,
    textSize,
    totalFills,
  });

  // Serialize fills
  let fillOffset = leafOffset + LEAF_ATTR_SIZE;
  fills.forEach((fill) => {
    if (fill.type === "solid") {
      const argb = hexToU32ARGB(fill.color, fill.opacity);
      dview.setUint8(fillOffset, 0x00, true); // Fill type: solid
      dview.setUint32(fillOffset + 4, argb, true);
      fillOffset += FILL_SIZE; // Move to the next fill
    }
  });

  // Add text content
  const textOffset = metadataSize;
  heap.set(textBuffer, textOffset);

  // Call the WebAssembly function
  Module._set_shape_text_content();
}
