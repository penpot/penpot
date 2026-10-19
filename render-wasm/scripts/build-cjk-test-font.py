#!/usr/bin/env python3
"""Build the synthetic CJK language fixture (requires fonttools).

The one Han character has distinct JAN, ZHS and KOR localized glyphs and
advances. This tests language propagation without a system font dependency.
All outlines are original rectangles; no external font data is included.
"""

from pathlib import Path

from fontTools.feaLib.builder import addOpenTypeFeaturesFromString
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen


def main():
    builder = FontBuilder(1000, isTTF=True)
    names = [".notdef", "base", "japanese", "chinese", "korean", "square", "space", "thirdEm"]
    builder.setupGlyphOrder(names)
    cmap = {ord(char): "square" for char in "文あっ한국어문장한글한「」，。"}
    cmap.update({0x4E2D: "base", 0x20: "space", 0x2004: "thirdEm"})
    builder.setupCharacterMap(cmap)
    glyphs = {}
    for index, name in enumerate(names):
        pen = TTGlyphPen(None)
        pen.moveTo((50, 0))
        pen.lineTo((350 + index * 20, 0))
        pen.lineTo((350 + index * 20, 700))
        pen.lineTo((50, 700))
        pen.closePath()
        glyphs[name] = pen.glyph()
    builder.setupGlyf(glyphs)
    metrics = {name: (400 + index * 100, 50) for index, name in enumerate(names)}
    metrics.update({"square": (1000, 50), "space": (500, 50), "thirdEm": (333, 50)})
    builder.setupHorizontalMetrics(metrics)
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({
        "familyName": "Penpot CJK Test",
        "styleName": "Regular",
        "uniqueFontIdentifier": "PenpotCJKTest",
        "fullName": "Penpot CJK Test Regular",
        "psName": "PenpotCJKTest-Regular",
    })
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200,
                     usWinAscent=800, usWinDescent=200)
    builder.setupPost()
    builder.setupMaxp()
    addOpenTypeFeaturesFromString(builder.font, """
        languagesystem DFLT dflt;
        languagesystem hani JAN;
        languagesystem hani ZHS;
        languagesystem hani KOR;
        feature locl {
            script hani;
            language JAN; sub base by japanese;
            language ZHS; sub base by chinese;
            language KOR; sub base by korean;
        } locl;
    """)
    builder.font["head"].created = 3800000000
    builder.font["head"].modified = 3800000000
    builder.font.recalcTimestamp = False
    builder.save(Path(__file__).resolve().parents[1] / "src/fonts/cjk-locl-test.ttf")


if __name__ == "__main__":
    main()
