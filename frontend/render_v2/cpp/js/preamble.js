// Adds compile-time JS functions to augment Renderer interface.
(function (Renderer) {
  console.log("preamble", Renderer);

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
    this._SetObjects(objects.cnt);
    for (let index = 0; index < objects.cnt; index++) {
      const object = objects.arr[index * 2 + 1];
      this._SetObject(
        index,
        object.selrect.x,
        object.selrect.y,
        object.selrect.width,
        object.selrect.height,
      );
    }
  };

  Renderer.drawCanvas = function drawCanvas(vbox, zoom, objects) {
    this._DrawCanvas(vbox.x, vbox.y, zoom);
  };
