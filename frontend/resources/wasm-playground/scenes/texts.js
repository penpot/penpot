import {
  ROOT,
  argb,
  defaultCase,
  finishScene,
  randomSource,
  serializeText,
  stroke,
  uploadScene,
  validateCount,
  validateParameters,
} from "./shared.js";

export default {
  id: "texts",
  version: 1,
  description:
    "Seeded multiline text using the renderer's embedded fallback font",
  cases: [
    defaultCase(
      {
        count: 100,
        width: 1920,
        height: 1080,
        minWidth: 20,
        maxWidth: 500,
        minHeight: 20,
        maxHeight: 100,
        minFontSize: 10,
        maxFontSize: 60,
        maxLines: 5,
        maxWords: 10,
        words: [
          "Hello",
          "World",
          "Penpot",
          "Canvas",
          "Text",
          "Shape",
          "Random",
          "Line",
        ],
      },
      { anchor: [960, 540], panDistance: [120, 60] },
    ),
  ],
  createScene(params, seed) {
    const dimensions = [
      "width",
      "height",
      "minWidth",
      "maxWidth",
      "minHeight",
      "maxHeight",
      "minFontSize",
      "maxFontSize",
      "maxLines",
      "maxWords",
    ];
    validateParameters(params, ["count", "words", ...dimensions]);
    if (
      !dimensions.every(
        (key) => Number.isFinite(params[key]) && params[key] > 0,
      ) ||
      !Number.isSafeInteger(params.maxLines) ||
      !Number.isSafeInteger(params.maxWords) ||
      params.minWidth >= params.maxWidth ||
      params.minHeight >= params.maxHeight ||
      params.minFontSize >= params.maxFontSize ||
      !Array.isArray(params.words) ||
      !params.words.length ||
      !params.words.every((word) => typeof word === "string" && word.length)
    ) {
      throw new Error("Invalid text parameters");
    }
    const count = validateCount(params.count);
    const rng = randomSource(seed);
    const shapes = [];
    for (let i = 0; i < count; i++) {
      const id = rng.id();
      const x = rng.int(0, params.width);
      const y = rng.int(0, params.height);
      const width = rng.int(params.minWidth, params.maxWidth);
      const height = rng.int(params.minHeight, params.maxHeight);
      const strokes = [];
      if (rng.float(0, 1) < 0.3) {
        const strokeCount = rng.int(1, 3);
        for (let j = 0; j < strokeCount; j++)
          strokes.push(
            stroke(
              rng.int(1, 10),
              argb(rng.int(0, 0x1000000), rng.float(0.1, 1)),
            ),
          );
      }
      const fontSize = rng.float(params.minFontSize, params.maxFontSize);
      const lines = Array.from(
        { length: rng.int(1, params.maxLines + 1) },
        () =>
          Array.from(
            { length: rng.int(1, params.maxWords + 1) },
            () => params.words[rng.int(0, params.words.length)],
          ).join(" "),
      );
      const text = lines.join("\n");
      shapes.push({
        id,
        parent: ROOT,
        type: 5,
        bounds: [x, y, x + width, y + height],
        strokes,
        text,
        textBytes: serializeText(
          text,
          fontSize,
          argb(rng.int(0, 0x1000000), rng.float(0.5, 1)),
        ),
      });
    }
    return finishScene(shapes);
  },
  upload: uploadScene,
};
