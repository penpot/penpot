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
  },
  wapi_notifyTilesRenderComplete: function wapi_notifyTilesRenderComplete() {
    // The corresponding listener lives on `document` (main thread), so in a
    // worker context we simply skip the dispatch instead of crashing.
    if (typeof WorkerGlobalScope !== 'undefined' && self instanceof WorkerGlobalScope) {
      return;
    }
    document.dispatchEvent(new CustomEvent('penpot:wasm:tiles-complete'));
  }
});
