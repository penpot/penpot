import { defaultCase, randomShapes, uploadScene } from "./shared.js";

export default {
  id: "plus",
  version: 1,
  description: "Seeded horizontal and vertical stroked line paths",
  cases: [
    defaultCase(
      { count: 1000, width: 1920, height: 1080, minSize: 20, maxSize: 200 },
      { anchor: [960, 540], panDistance: [120, 60] },
    ),
  ],
  createScene: (params, seed) => randomShapes("plus", params, seed),
  upload: uploadScene,
};
