// Adds compile-time JS functions to augment Renderer interface.
(function (Renderer) {
  console.log("preamble", Renderer);
  Renderer.setCanvas = function setCanvas(canvas, attrs) {
    console.log("GL", GL);
    debugger
    const context = GL.createContext(canvas, attrs);
    if (!context) {
      throw new Error('Could not create a new WebGL context')
    }
    GL.makeContextCurrent(context);

    // Emscripten does not enable this by default and Skia needs this
    // to handle certain GPU corner cases.
    GL.currentContext.GLctx.getExtension('WEBGL_debug_renderer_info');

    console.log("setCanvas", canvas, attrs);
    const gr  = this._MakeGrContext();
    console.log("gr", gr);
  };
