addToLibrary({
  wapi_requestAnimationFrame: function wapi_requestAnimationFrame() {
    return window.requestAnimationFrame(Module._process_animation_frame);
  },
  wapi_cancelAnimationFrame: function wapi_cancelAnimationFrame(frameId) {
    return window.cancelAnimationFrame(frameId);
  }
});
