// Adds compile-time JS functions to augment Renderer interface.
(function (Renderer) {
  console.log("preamble", Renderer);
  Renderer.setCanvas = function setCanvas(canvas, attrs) {
    console.log("GL", GL);
    const context = GL.createContext(canvas, attrs);
    if (!context) {
      throw new Error('Could not create a new WebGL context')
    }
    console.log("setCanvas", canvas, attrs);
    const gr  = this._MakeGrContext();
    console.log("gr", gr);
  };
