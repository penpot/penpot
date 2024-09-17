if (!('dynamicImport' in window)) {
  window.dynamicImport = function(uri) {
    return import(uri);
  }
};
