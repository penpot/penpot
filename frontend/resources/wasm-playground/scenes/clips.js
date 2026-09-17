import {
  ROOT,
  argb,
  defaultCase,
  finishScene,
  randomSource,
  rectangle,
  stroke,
  uploadScene,
  validateParameters,
} from "./shared.js";

export default {
  id: "clips",
  version: 1,
  description:
    "Fixed clipped frame with rounded corners, blur, stroke and drop shadow",
  cases: [
    defaultCase({ count: 2 }, { anchor: [320, 280], panDistance: [60, 30] }),
  ],
  createScene(params, seed) {
    validateParameters(params, ["count"]);
    if (params.count !== 2) throw new Error("The clips scene requires count=2");
    const rng = randomSource(seed);
    const frame = {
      ...rectangle(rng.id(), ROOT, [200, 200, 450, 450], 0xffee0d32),
      type: 0,
      clip: true,
      corners: [50, 50, 50, 50],
      strokes: [stroke(25, 0xff000000)],
      blur: [1, false, 4],
      shadows: [[argb(0x000000, 0.2), 4, 40, 80, 80, 0, false]],
    };
    const child = {
      ...rectangle(rng.id(), frame.id, [100, 100, 300, 300], 0xff003df7),
      blur: [1, false, 40],
    };
    // The old fixture's second rectangle was unreachable and is deliberately omitted.
    frame.children = [child.id];
    return finishScene([frame, child]);
  },
  upload: uploadScene,
};
