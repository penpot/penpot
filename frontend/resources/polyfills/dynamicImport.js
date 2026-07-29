if (!('dynamicImport' in globalThis)) {
  globalThis.dynamicImport = function(uri) {
    return import(uri);
  }
};

var global = globalThis;
