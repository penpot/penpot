export class Transit {
  static parse(value) {
    if (typeof value !== "string") return value;

    if (value.startsWith("~")) return value.slice(2);

    return value;
  }

  static get(object, ...path) {
    let aux = object;
    for (const name of path) {
      if (typeof name !== "string") {
        if (!(name in aux)) {
          return undefined;
        }
        aux = aux[name];
      } else {
        const transitName = `~:${name}`;
        if (!(transitName in aux)) {
          return undefined;
        }
        aux = aux[transitName];
      }
    }
    return this.parse(aux);
  }
}
