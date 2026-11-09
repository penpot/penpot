//! Collects the text behind each drawn glyph, per font. Skia's PDF backend
//! maps substituted glyphs (ligatures, contextual alternates) to U+0000.

use std::collections::HashMap;

use skia_safe as skia;

use crate::shapes::Type;
use crate::state::ShapesPoolRef;
use crate::uuid::Uuid;

#[derive(Debug, Clone)]
struct GlyphText {
    text: String,
    /// The glyph is the only one drawn for its cluster.
    single_glyph: bool,
    typeface: skia::typeface::TypefaceId,
    /// Two typefaces sharing a font name disagree on this glyph.
    conflict: bool,
}

/// Text for each glyph, keyed by the font name Skia writes in `/BaseFont`
/// (without the subset tag).
#[derive(Debug, Default)]
pub struct GlyphTexts {
    fonts: HashMap<String, HashMap<skia::GlyphId, GlyphText>>,
}

impl GlyphTexts {
    /// Text that should replace the current mapping of `glyph` in `font`, if
    /// any. `current` is the mapping Skia wrote (empty or `"\0"` when unknown).
    pub fn replacement(&self, font: &str, glyph: skia::GlyphId, current: &str) -> Option<&str> {
        let entry = self.fonts.get(font)?.get(&glyph)?;
        if entry.conflict || entry.text.is_empty() || entry.text == current {
            return None;
        }
        let unmapped = current.is_empty() || current == "\0";
        // A single glyph standing for several characters is a ligature: the
        // font's `cmap` entry (e.g. U+FB02 for "fl") is not what was typed.
        let ligature = entry.single_glyph && entry.text.chars().count() > 1;
        (unmapped || ligature).then_some(entry.text.as_str())
    }

    pub fn is_empty(&self) -> bool {
        self.fonts.is_empty()
    }

    #[cfg(test)]
    pub(super) fn with_glyph(mut self, font: &str, glyph: skia::GlyphId, text: &str) -> Self {
        let value = GlyphText {
            text: text.to_string(),
            single_glyph: true,
            typeface: 1,
            conflict: false,
        };
        self.insert(font, glyph, value);
        self
    }

    fn insert(&mut self, font: &str, glyph: skia::GlyphId, value: GlyphText) {
        let glyphs = self.fonts.entry(font.to_string()).or_default();
        match glyphs.get_mut(&glyph) {
            None => {
                glyphs.insert(glyph, value);
            }
            // Same typeface, different text: keep the first one, as Skia does.
            Some(existing)
                if existing.typeface != value.typeface && existing.text != value.text =>
            {
                existing.conflict = true;
            }
            Some(_) => {}
        }
    }

    /// Records the glyphs of a laid-out paragraph. `text` is the UTF-8 text the
    /// paragraph was built from.
    pub fn add_paragraph(&mut self, text: &str, paragraph: &mut skia::textlayout::Paragraph) {
        let mut runs: Vec<Run> = Vec::new();
        paragraph.visit(|_, info| {
            let Some(info) = info else {
                return;
            };
            let typeface = info.font().typeface();
            runs.push(Run {
                typeface: typeface.unique_id(),
                font_name: pdf_font_name(&typeface),
                glyphs: info.glyphs().to_vec(),
                starts: info.utf8_starts().iter().map(|s| *s as usize).collect(),
            });
        });
        self.add_runs(text, &runs);
    }

    fn add_runs(&mut self, text: &str, runs: &[Run]) {
        // Cluster starts of the whole paragraph: a cluster ends where the next
        // one starts, and that one may sit in another run or line.
        let mut boundaries: Vec<usize> = runs
            .iter()
            .flat_map(|run| run.starts.iter().copied())
            .chain([text.len()])
            .collect();
        boundaries.sort_unstable();
        boundaries.dedup();

        for run in runs {
            let count = run.glyphs.len();
            for (i, &glyph) in run.glyphs.iter().enumerate() {
                let start = run.starts[i];
                if i > 0 && run.starts[i - 1] == start {
                    continue;
                }
                let single_glyph = i + 1 >= count || run.starts[i + 1] != start;
                if let Some(cluster) = cluster_text(text, &boundaries, start) {
                    self.insert(
                        &run.font_name,
                        glyph,
                        GlyphText {
                            text: cluster.to_string(),
                            single_glyph,
                            typeface: run.typeface,
                            conflict: false,
                        },
                    );
                }
            }
        }
    }
}

struct Run {
    typeface: skia::typeface::TypefaceId,
    font_name: String,
    glyphs: Vec<skia::GlyphId>,
    starts: Vec<usize>,
}

/// Text of the cluster starting at `start`. Whitespace ends it: spaces trimmed
/// at a line break are not drawn, so their start is missing from `boundaries`.
fn cluster_text<'a>(text: &'a str, boundaries: &[usize], start: usize) -> Option<&'a str> {
    let end = boundaries
        .iter()
        .copied()
        .find(|b| *b > start)
        .unwrap_or(text.len());
    let cluster = text.get(start..end)?;
    match cluster
        .char_indices()
        .skip(1)
        .find(|(_, c)| c.is_whitespace())
    {
        Some((at, _)) => Some(&cluster[..at]),
        None => Some(cluster),
    }
}

/// The name Skia's PDF backend writes for a typeface: its PostScript name, or
/// its family name when it has none.
fn pdf_font_name(typeface: &skia::Typeface) -> String {
    typeface
        .post_script_name()
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| typeface.family_name())
}

/// Collects the glyph texts of every visible text shape under `root`.
pub fn collect(tree: ShapesPoolRef, root: &Uuid) -> GlyphTexts {
    let mut glyph_texts = GlyphTexts::default();
    let Some(root_shape) = tree.get(root) else {
        return glyph_texts;
    };
    for id in root_shape.all_children_iter(tree, false, true) {
        let Some(shape) = tree.get(&id) else {
            continue;
        };
        let Type::Text(text_content) = &shape.shape_type else {
            continue;
        };
        if shape.hidden {
            continue;
        }
        let text_content = text_content.new_bounds(shape.selrect());
        let width = text_content.get_width(shape.selrect().width());
        for group in text_content
            .paragraph_builder_group_from_text(None)
            .iter_mut()
        {
            for builder in group.iter_mut() {
                // `build` takes the text out of the builder.
                let text = builder.get_text().to_string();
                let mut paragraph = builder.build();
                paragraph.layout(width);
                glyph_texts.add_paragraph(&text, &mut paragraph);
            }
        }
    }
    glyph_texts
}

/// Merges glyph texts from every root in `ids` (export-frames / multi PDF).
pub fn collect_many(tree: ShapesPoolRef, ids: &[Uuid]) -> GlyphTexts {
    let mut result = GlyphTexts::default();
    for id in ids {
        result.merge(collect(tree, id));
    }
    result
}

impl GlyphTexts {
    fn merge(&mut self, other: GlyphTexts) {
        for (font, glyphs) in other.fonts {
            for (glyph, value) in glyphs {
                self.insert(&font, glyph, value);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(glyphs: &[u16], starts: &[usize]) -> Run {
        Run {
            typeface: 1,
            font_name: "Font".to_string(),
            glyphs: glyphs.to_vec(),
            starts: starts.to_vec(),
        }
    }

    #[test]
    fn ligature_glyph_maps_to_its_whole_cluster() {
        let mut texts = GlyphTexts::default();
        // "office": o, ffi (one glyph), c, e
        texts.add_runs("office", &[run(&[10, 20, 30, 40], &[0, 1, 4, 5, 6])]);

        assert_eq!(texts.replacement("Font", 20, "\0"), Some("ffi"));
        assert_eq!(texts.replacement("Font", 20, "\u{FB03}"), Some("ffi"));
        assert_eq!(texts.replacement("Font", 10, "o"), None);
        assert_eq!(texts.replacement("Font", 30, "\0"), Some("c"));
    }

    #[test]
    fn single_char_glyph_with_cmap_entry_keeps_it() {
        let mut texts = GlyphTexts::default();
        texts.add_runs("a", &[run(&[5], &[0, 1])]);

        assert_eq!(texts.replacement("Font", 5, "\u{00E1}"), None);
        assert_eq!(texts.replacement("Font", 5, ""), Some("a"));
    }

    #[test]
    fn multi_glyph_cluster_only_fills_the_first_glyph() {
        let mut texts = GlyphTexts::default();
        // "é" drawn as base + mark glyphs sharing one cluster.
        texts.add_runs("é", &[run(&[7, 8], &[0, 0, 2])]);

        assert_eq!(texts.replacement("Font", 7, "\0"), Some("é"));
        assert_eq!(texts.replacement("Font", 8, "\0"), None);
    }

    #[test]
    fn clusters_end_at_starts_from_other_runs() {
        let mut texts = GlyphTexts::default();
        // Right-to-left run: glyph order is the reverse of text order, and its
        // last cluster ends where the next run starts.
        texts.add_runs(
            "abcd",
            &[run(&[3, 2], &[1, 0, 0]), run(&[4, 5], &[2, 3, 4])],
        );

        assert_eq!(texts.replacement("Font", 3, "\0"), Some("b"));
        assert_eq!(texts.replacement("Font", 2, "\0"), Some("a"));
    }

    #[test]
    fn cluster_stops_at_whitespace_dropped_at_line_breaks() {
        let mut texts = GlyphTexts::default();
        // "lo world" wraps after "lo": the space at 2 is not drawn, so the
        // "o" cluster would run until "w" without the whitespace stop.
        texts.add_runs("lo world", &[run(&[8, 9], &[0, 1, 3])]);

        assert_eq!(texts.replacement("Font", 9, "\0"), Some("o"));
    }

    #[test]
    fn conflicting_typefaces_with_the_same_name_are_ignored() {
        let mut texts = GlyphTexts::default();
        texts.add_runs("a", &[run(&[5], &[0, 1])]);
        let mut other = run(&[5], &[0, 1]);
        other.typeface = 2;
        texts.add_runs("b", &[other]);

        assert_eq!(texts.replacement("Font", 5, "\0"), None);
    }
}
