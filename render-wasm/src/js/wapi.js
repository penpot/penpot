addToLibrary({
  wapi_requestAnimationFrame: function wapi_requestAnimationFrame() {
    if (typeof WorkerGlobalScope !== 'undefined' && self instanceof WorkerGlobalScope) {
      setTimeout(Module._process_animation_frame);
    } else {
      return window.requestAnimationFrame(Module._process_animation_frame);
    }
  },
  wapi_cancelAnimationFrame: function wapi_cancelAnimationFrame(frameId) {
    if (typeof WorkerGlobalScope !== 'undefined' && self instanceof WorkerGlobalScope) {
      clearTimeout(frameId);
    } else {
      return window.cancelAnimationFrame(frameId);
    }
  }
});
