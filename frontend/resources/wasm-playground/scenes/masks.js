import {
  ROOT,
  defaultCase,
  finishScene,
  randomSource,
  rectangle,
  uploadScene,
  validateParameters,
} from "./shared.js";

export default {
  id: "masks",
  version: 1,
  description: "Fixed masked group containing a rectangle and an ellipse",
  cases: [
    defaultCase({ count: 4 }, { anchor: [320, 280], panDistance: [60, 30] }),
  ],
  createScene(params, seed) {
    validateParameters(params, ["count"]);
    if (params.count !== 4) throw new Error("The masks scene requires count=4");
    const rng = randomSource(seed);
    const group = {
      id: rng.id(),
      parent: ROOT,
      type: 1,
      bounds: [319, 144, 544, 332],
      masked: true,
    };
    const outside = rectangle(rng.id(), ROOT, [100, 100, 200, 200], 0xffaabbcc);
    const mask = rectangle(
      rng.id(),
      group.id,
      [319, 144, 544, 332],
      0xff0c44ea,
    );
    const ellipse = {
      ...rectangle(rng.id(), group.id, [98, 214, 426, 475], 0xffb1b2b5),
      type: 6,
    };
    group.children = [mask.id, ellipse.id];
    return finishScene([group, outside, mask, ellipse]);
  },
  upload: uploadScene,
};
