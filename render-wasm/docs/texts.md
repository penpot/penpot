# Texts

## Paragraphs and Text Leaves

Penpot uses Skia's [Paragraph](https://github.com/google/skia/blob/main/modules/skparagraph/src/ParagraphImpl.cpp) for rendering text. Each text shape can contain multiple styles, so every paragraph manages both overall and per-node style information. We use [ParagraphBuilder](https://github.com/google/skia/blob/main/modules/skparagraph/src/ParagraphBuilderImpl.cpp) for that.

- **Paragraph Style:** The style for the entire paragraph (such as alignment or line height) is set first. See [ParagraphStyle](https://github.com/google/skia/blob/main/modules/skparagraph/src/ParagraphStyle.cpp).
- **Text Nodes (Leaves) Style:** For each text node (referred to as a "leaf" internally), its specific style (like color or font weight) is processed, added to the builder, and incorporated into the final paragraph. See [TextStyle](https://github.com/google/skia/blob/main/modules/skparagraph/src/TextStyle.cpp).

Some styles, like strokes, are applied to the whole paragraph. Others, like fills, can be applied to individual text nodes, allowing for complex mixed-style designs within a single shape.

## Fills and Strokes

Penpot uses Skia's paint API for both fills and strokes.

- **Strokes:** To achieve outline effects (inner, outer, or center), we create a separate paragraph for each paint type needed. We don't use the built-in function to draw strokes, as we need to support multiple strokes per text and this approach allows more flexibility. Strokes are always applied to the entire paragraph.
- **Fills:** For fills, each fill is converted to a shader and applied to a paint, which is then used to style the text. When multiple fills are present, their shaders are merged so that all fills are applied together, layering one over the other.

## Shadows

Similar to strokes, we don't use the shadow utility from the text API to draw shadows. As we do with other shapes, we create a copy of the shape (a paragraph) and set the `ImageFilter` of the shadow as paint. Both inner and drop shadows are supported. For each shadow type, the paragraph is built with the appropriate shadow paint applied to its respective surface.

## Fonts

Penpot offers font handling for both Google Fonts and custom fonts, using Skia’s `FontCollection` and `TypefaceFontProvider` within our `FontStore`.

- **Dynamic Loading:** Fonts are loaded dynamically as needed. When a shape requires a specific font, the font data is transferred to WebAssembly (WASM) for processing.
- **Fallback Mechanism:** Skia requires explicit font data for proper Unicode rendering, so we cannot rely on browser fallback as with SVG. We detect the language used and automatically add the appropriate Noto Sans font as a fallback. If the user’s selected fonts cannot render the text, Skia’s fallback mechanism will try the next available font in the list.
- **Emoji Support:** For emoji characters, we use Noto Color Emoji by default. Ideally in the future, users will be able to select custom emoji fonts instead of Noto Sans as default, as the code is ready for this scenario.

## Texts as Paths

In Skia, it's possible to render text as paths in different ways. However, to preserve paragraph properties, we need to convert text nodes to `TextBlob`, then convert them to `Path`, and finally render them. This is necessary to ensure each piece of text is rendered in its correct position within the paragraph layout. We achieve this by using **Line Metrics** and **Style Metrics**, which allow us to iterate through each text element and read its dimensions, position, and style properties.

This feature is not currently activated, but it is explained step by step in [render-wasm/src/shapes/text_paths.rs](/render-wasm/src/shapes/text_paths.rs).

1. Get the paragraph and set the layout width. This is important because the rest of the metrics depend on setting the layout correctly.
2. Iterate through each line in the paragraph. Paragraph text style is retrieved through `LineMetrics`, which is why we go line by line.
3. Get the styles present in the line for each text leaf. `StyleMetrics` contain the style for individual text leaves, which we convert to `TextBlob` and then to `Path`.
4. Finally, `text::render` should paint all the paths on the canvas.


## References

- [Noto: A typeface for the world](https://fonts.google.com/noto)
- [Noto Color Emoji](https://fonts.google.com/noto/specimen/Noto+Color+Emoji)
