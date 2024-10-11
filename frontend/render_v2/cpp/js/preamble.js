// Adds compile-time JS functions to augment Renderer interface.
(function (Renderer) {
  console.log("preamble", Renderer);

  const LCG_MULTIPLIER = 1103515245;
  const LCG_INCREMENT = 12345;
  const LCG_MODULUS = Math.pow(2, 31);
  const LCG_MASK = (LCG_MODULUS - 1);

  function lcg(x, a, c, m) {
    return (x * a + c) % m;
  }

  class Random {
    constructor(seed) {
      this._seed = seed;
    }

    value() {
      this._seed = lcg(this._seed, LCG_MULTIPLIER, LCG_INCREMENT, LCG_MODULUS);
      return (this._seed & LCG_MASK) / LCG_MODULUS;
    }
  }

  const random = new Random(0)

  // Sets canvas.
  Renderer.setCanvas = function setCanvas(canvas, attrs) {
    const context = GL.createContext(canvas, attrs);
    if (!context) {
      throw new Error('Could not create a new WebGL context')
    }
    GL.makeContextCurrent(context);

    // Emscripten does not enable this by default and Skia needs this
    // to handle certain GPU corner cases.
    GL.currentContext.GLctx.getExtension('WEBGL_debug_renderer_info');

    // Initializes everything needed.
    this._InitCanvas(canvas.width, canvas.height);
  };

  Renderer.setObjects = function setObjects(vbox, zoom, objects) {
    // this._SetObjects(objects.cnt);
    const numObjects = 20_000;
    this._SetObjects(numObjects);
    for (let index = 0; index < numObjects; index++) {
      // const object = objects.arr[index * 2 + 1];
      this._SetObjectRect(
        index,
        // object.selrect.x,
        random.value() * 2000,
        // object.selrect.y,
        random.value() * 2000,
        // object.selrect.width,
        random.value() * 200,
        // object.selrect.height,
        random.value() * 200
      );
    }
  };

  Renderer.drawCanvas = function drawCanvas(vbox, zoom, objects) {
    performance.mark('draw-canvas:start');
    this._DrawCanvas(vbox.x, vbox.y, zoom);
    performance.mark('draw-canvas:end');
    const { duration } = performance.measure('draw-canvas', 'draw-canvas:start', 'draw-canvas:end');
    console.log('draw-canvas', `${duration}ms`);
  };
