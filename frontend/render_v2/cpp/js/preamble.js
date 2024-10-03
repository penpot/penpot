// Adds compile-time JS functions to augment Renderer interface.
(function (Renderer) {
  console.log("preamble", Renderer);

  //
  let gr;
  let surface;

  Renderer.setCanvas = function setCanvas(canvas, attrs) {
    console.log("GL", GL);
    const context = GL.createContext(canvas, attrs);
    if (!context) {
      throw new Error('Could not create a new WebGL context')
    }
    GL.makeContextCurrent(context);

    // Emscripten does not enable this by default and Skia needs this
    // to handle certain GPU corner cases.
    GL.currentContext.GLctx.getExtension('WEBGL_debug_renderer_info');

    console.log("setCanvas", canvas, attrs);
    gr = this._MakeGrContext();
    console.log("gr", gr);

    surface = this._MakeOnScreenGLSurface(gr, canvas.width, canvas.height);
    console.log("surface", surface);
    if (!surface) {
      throw new Error('Cannot initialize surface')
    }
  };

  function wasMalloced(obj) {
    return obj && obj['_ck'];
  }

  function copy1dArray(arr, dest, ptr) {
    if (!arr || !arr.length) return null;
    if (wasMalloced(arr)) {
      return arr.byteOffset;
    }
    const bytesPerElement = Renderer[dest].BYTES_PER_ELEMENT;
    if (!ptr) {
      ptr = Renderer._malloc(arr.length * bytesPerElement);
    }
    Renderer[dest].set(arr, ptr / bytesPerElement);
    return ptr;
  }

	function copyRectToWasm(fourFloats, ptr) {
	  return copy1dArray(fourFloats, 'HEAPF32', ptr || null);
	}

  function copyColorToWasm(color4f, ptr) {
    return copy1dArray(color4f, 'HEAPF32', ptr || null);
  }

  Renderer.drawCanvas = function drawCanvas(vbox, zoom, objects) {
    console.log("vbox", vbox);
    console.log("zoom", zoom);
    if (!surface) {
      throw new Error('Surface uninitialized');
    }

    console.log("renderer", Renderer);
    console.log("surface", surface);

    // Esto es una ÑAPA terrible, no me gusta.
    if (!Renderer.Paint.prototype.setColor) {
      Renderer.Paint.prototype.setColor = function(color4f, colorSpace = null) {
        const cPtr = copyColorToWasm(color4f);
        this._setColor(cPtr, colorSpace);
      }
    }

    const paint = new Renderer.Paint();
    paint.setColor(Float32Array.of(1.0, 0, 0, 1.0));
    paint.setStyle(Renderer.PaintStyle.Fill);
    paint.setAntiAlias(true);
    console.log("paint", paint);

    const canvas = surface._getCanvas();
    console.log("canvas", canvas);

    const cPtr = copyColorToWasm(Float32Array.of(0.0, 0.0, 0.0, 1.0))
    canvas._clear(cPtr);
    console.log("canvas cleared");

    for (const { val: object } of objects) {
      console.log("object", object);
      const rr = Float32Array.of(object.selrect.x, object.selrect.y, object.selrect.width, object.selrect.height);

      const rPtr = copyRectToWasm(rr);
      canvas._drawRect(rPtr, paint);
    }
  };
