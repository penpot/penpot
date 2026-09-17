import { argb, defaultCase, randomShapes, uploadScene } from "./shared.js";

export default {
  id: "shadows",
  version: 1,
  description: "Seeded rectangles with two drop shadows and an inner shadow",
  cases: [
    defaultCase(
      {
        count: 1000,
        width: 1920,
        height: 1080,
        minSize: 20,
        maxSize: 100,
        shadows: [
          [argb(0xdedede, 0.33), 4, -2, 0, 2, 0, false],
          [argb(0xdedede), 12, -8, 0, 12, 0, false],
          [argb(0x002046, 0.12), 12, -8, 0, -4, 1, false],
        ],
      },
      { anchor: [960, 540], panDistance: [120, 60] },
    ),
  ],
  createScene: (params, seed) => randomShapes("shadows", params, seed),
  upload: uploadScene,
};
