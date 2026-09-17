import { defaultCase, randomShapes, uploadScene } from "./shared.js";

export default {
  id: "rects",
  version: 1,
  description: "Seeded rectangles with translucent fills and centered strokes",
  cases: [
    defaultCase(
      { count: 1000, width: 1920, height: 1080, minSize: 20, maxSize: 100 },
      { anchor: [960, 540], panDistance: [120, 60] },
    ),
  ],
  createScene: (params, seed) => randomShapes("rects", params, seed),
  upload: uploadScene,
};
