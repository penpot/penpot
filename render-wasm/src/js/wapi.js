addToLibrary({
  wapi_notifyTilesRenderComplete: function wapi_notifyTilesRenderComplete() {
    // The corresponding listener lives on `document` (main thread), so in a
    // worker context we simply skip the dispatch instead of crashing.
    if (typeof WorkerGlobalScope !== 'undefined' && self instanceof WorkerGlobalScope) {
      return;
    }
    document.dispatchEvent(new CustomEvent('penpot:wasm:tiles-complete'));
  }
});
