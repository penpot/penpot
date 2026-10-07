use std::collections::HashMap;

use super::*;

/// Extra spacing for long horizontal ruby, per span of a paragraph.
#[derive(Debug, Clone, Default)]
pub(crate) struct HorizontalRubySpacing {
    /// Letter-spacing added to characters of the layout texts: (char index,
    /// amount) per span.
    pub adjustments: Vec<Vec<(usize, f32)>>,
    /// Gap added between the base characters of each ruby span.
    pub base_gaps: Vec<f32>,
    /// Spacing added before each ruby base, on the previous character: half
    /// a gap, or none at a paragraph start, where the base takes it after.
    pub leading_gaps: Vec<f32>,
    /// Overhang room (before, after) of each ruby span over its neighbours.
    pub rooms: Vec<(f32, f32)>,
}

/// The span that owns a character of the layout `texts`, its char index and
/// the character, searching from (span, index) in `step` direction and
/// skipping inserted word joiners.
fn layout_neighbour(texts: &[String], span: usize, forward: bool) -> Option<(usize, usize, char)> {
    let real =
        |(index, ch): (usize, char)| (!kinsoku::is_inserted_zero_width(ch)).then_some((index, ch));
    if forward {
        texts
            .iter()
            .enumerate()
            .skip(span + 1)
            .find_map(|(owner, text)| {
                text.chars()
                    .enumerate()
                    .find_map(real)
                    .map(|(index, ch)| (owner, index, ch))
            })
    } else {
        texts
            .iter()
            .enumerate()
            .take(span)
            .rev()
            .find_map(|(owner, text)| {
                let chars: Vec<char> = text.chars().collect();
                chars
                    .iter()
                    .copied()
                    .enumerate()
                    .rev()
                    .find_map(real)
                    .map(|(index, ch)| (owner, index, ch))
            })
    }
}

/// Spacing that makes room for horizontal ruby longer than its base. The
/// reading overhangs its neighbours within their `ruby_overhang_room`, less
/// what an earlier reading already hangs over the same character (JLREQ
/// Fig 138); the rest spreads the base (JLREQ §3.3.8): an equal gap between base
/// characters and half a gap at each end, the leading half on the previous
/// character.
pub(crate) fn horizontal_ruby_spacing(
    paragraph: &Paragraph,
    texts: &[String],
    offset_map: &kinsoku::OffsetMap,
) -> HorizontalRubySpacing {
    if !paragraph.children().iter().any(TextSpan::has_ruby) {
        return HorizontalRubySpacing::empty(texts.len());
    }
    let fallback_families: Vec<String> = get_fallback_fonts().iter().cloned().collect();
    horizontal_ruby_spacing_with(
        paragraph,
        texts,
        offset_map,
        get_resources().fonts.font_provider(),
        &fallback_families,
    )
}

impl HorizontalRubySpacing {
    fn empty(spans: usize) -> Self {
        Self {
            adjustments: vec![Vec::new(); spans],
            base_gaps: vec![0.0; spans],
            leading_gaps: vec![0.0; spans],
            rooms: vec![(0.0, 0.0); spans],
        }
    }
}

/// `horizontal_ruby_spacing` with explicit fonts.
pub(super) fn horizontal_ruby_spacing_with(
    paragraph: &Paragraph,
    texts: &[String],
    offset_map: &kinsoku::OffsetMap,
    font_provider: &skia::textlayout::TypefaceFontProvider,
    fallback_families: &[String],
) -> HorizontalRubySpacing {
    let spans = paragraph.children();
    let mut spacing = HorizontalRubySpacing::empty(texts.len());
    let fallback_mgr = FontMgr::from(font_provider.clone());
    let advances = |span: &TextSpan, text: &str, font_size: f32| -> Vec<f32> {
        shape_segment_with_fallbacks(
            text,
            font_size,
            &span_font_families(span, fallback_families),
            font_provider,
            false,
            span.font_features,
            &fallback_mgr,
        )
        .iter()
        .flat_map(|run| run.advances.iter().copied())
        .collect()
    };
    // Overhang an earlier reading already takes on a character after it.
    let mut claimed: HashMap<(usize, usize), f32> = HashMap::new();
    let mut span_start = 0usize;
    for (index, (span, text)) in spans.iter().zip(texts).enumerate() {
        let text_start = span_start;
        span_start += text.encode_utf16().count();
        if !span.has_ruby() {
            continue;
        }
        // Source characters of the base; inserted spacing and joiners are not.
        let mut utf16 = text_start;
        let base: Vec<usize> = text
            .chars()
            .enumerate()
            .filter(|(_, ch)| {
                let inserted = offset_map.is_inserted(utf16);
                utf16 += ch.len_utf16();
                !inserted
            })
            .map(|(char_index, _)| char_index)
            .collect();
        let Some(&last) = base.last() else {
            continue;
        };
        let ruby_font_size = span.ruby_font_size();
        let base_width = advances(span, &span.apply_text_transform(), span.font_size)
            .iter()
            .sum::<f32>()
            + span.letter_spacing * base.len() as f32;
        // The painter sets the reading in pieces (see `RubyLine::paint`).
        let shaped = shape_segment_with_fallbacks(
            span.ruby_text(),
            ruby_font_size,
            &span_font_families(span, fallback_families),
            font_provider,
            false,
            span.font_features,
            &fallback_mgr,
        );
        let (_, pieces, _) =
            horizontal_ruby_pieces(&ruby_glyph_refs(span.ruby_text(), &shaped, ruby_font_size));
        let ruby_length: f32 = pieces.iter().sum();
        let previous = layout_neighbour(texts, index, false);
        let next = layout_neighbour(texts, index, true);
        let room = |neighbour: Option<(usize, usize, char)>| {
            neighbour.map_or(0.0, |(owner, _, ch)| {
                ruby_overhang_room(
                    span.ruby_overhang,
                    Some(ch),
                    spans[owner].has_ruby(),
                    spans[owner].font_size,
                    ruby_font_size,
                )
            })
        };
        let taken = previous
            .and_then(|(owner, char_index, _)| claimed.get(&(owner, char_index)))
            .copied()
            .unwrap_or(0.0);
        let rooms = ((room(previous) - taken).max(0.0), room(next));
        spacing.rooms[index] = rooms;
        if let Some((owner, char_index, _)) = next {
            let hang_after = ruby_overhangs(ruby_length - base_width, rooms).1;
            claimed.insert((owner, char_index), hang_after);
        }
        let gap = long_ruby_gap(ruby_length, base_width, rooms, base.len());
        if gap <= 0.0 {
            continue;
        }
        spacing.base_gaps[index] = gap;
        for &char_index in &base {
            let amount = if char_index == last { gap / 2.0 } else { gap };
            spacing.adjustments[index].push((char_index, amount));
        }
        match previous {
            Some((owner, char_index, _)) => {
                spacing.adjustments[owner].push((char_index, gap / 2.0));
                spacing.leading_gaps[index] = gap / 2.0;
            }
            None => spacing.adjustments[index].push((last, gap / 2.0)),
        }
    }
    spacing
}

/// Split `total` glyphs across segments proportionally to their extents
/// (rounded per segment, remainder to the last) so no glyph is dropped.
pub(super) fn split_counts_by_extent(extents: &[f32], total: usize) -> Vec<usize> {
    let mut counts = vec![0usize; extents.len()];
    if extents.is_empty() || total == 0 {
        return counts;
    }
    let sum: f32 = extents.iter().sum();
    if sum <= 0.0 {
        counts[extents.len() - 1] = total;
        return counts;
    }
    let mut assigned = 0usize;
    let last = extents.len() - 1;
    for (index, extent) in extents.iter().enumerate() {
        let count = if index == last {
            total - assigned
        } else {
            (((extent / sum) * total as f32).round() as usize).min(total - assigned)
        };
        counts[index] = count;
        assigned += count;
    }
    counts
}

/// (span index, builder-text range) of every span whose ruby is painted:
/// visible ruby on a base that is not warichu.
pub(super) fn horizontal_ruby_targets(
    paragraph: &Paragraph,
    offsets: &HorizontalOffsets,
) -> Vec<(usize, std::ops::Range<usize>)> {
    paragraph
        .children()
        .iter()
        .zip(&offsets.ranges)
        .filter(|(span, range)| span.has_ruby() && range.builder_start < range.builder_end)
        .map(|(_, range)| (range.span, range.builder_start..range.builder_end))
        .collect()
}

/// Paint of the pass that laid out the base: fill, stroke, shadow or mask.
/// `fallback` covers a base Skia kept no style metrics for.
pub(super) fn horizontal_ruby_paint(
    laid_out: &skia::textlayout::Paragraph,
    range: &HorizontalSpanRange,
    fallback: skia::Paint,
) -> skia::Paint {
    horizontal_span_style(laid_out, range).map_or(fallback, |style| style.foreground())
}

/// Ink edge of `glyph` (bottom when `over`, else top), used to attach ruby
/// ink to its base. Font-wide ascent/descent include leading, which leaves
/// ruby detached in many Japanese faces.
fn horizontal_ruby_ink_edge(font: &Font, glyph: GlyphId, fallback: f32, over: bool) -> f32 {
    let mut bounds = [skia::Rect::default()];
    font.get_bounds(&[glyph], &mut bounds, None);
    let bound = bounds[0];
    if bound.right > bound.left && bound.bottom > bound.top {
        if over {
            bound.bottom
        } else {
            bound.top
        }
    } else {
        fallback
    }
}

/// One shaped ruby glyph: (run index, glyph index in the run, advance,
/// whether it belongs to a Western word).
pub(super) type RubyGlyphRef = (usize, usize, f32, bool);

/// The glyphs of a reading shaped from `ruby_text`, in order.
pub(super) fn ruby_glyph_refs(
    ruby_text: &str,
    shaped: &[ShapedRun],
    ruby_font_size: f32,
) -> Vec<RubyGlyphRef> {
    shaped
        .iter()
        .enumerate()
        .flat_map(|(run_index, run)| {
            (0..run.glyphs.len()).map(move |glyph| {
                let advance = run.advances.get(glyph).copied().unwrap_or(ruby_font_size);
                let western = run
                    .clusters
                    .get(glyph)
                    .and_then(|cluster| ruby_text.get(*cluster as usize..))
                    .and_then(|rest| rest.chars().next())
                    .is_some_and(|ch| !is_upright_char(ch));
                (run_index, glyph, advance, western)
            })
        })
        .collect()
}

/// Pieces of a horizontal reading (`ruby_pieces`): each upright glyph in a
/// slot as wide as the widest upright glyph, each Western word whole.
/// Returns the slot, the piece extents and each glyph's piece and offset.
pub(super) fn horizontal_ruby_pieces(
    glyphs: &[RubyGlyphRef],
) -> (f32, Vec<f32>, Vec<(usize, f32)>) {
    let slot = glyphs
        .iter()
        .filter(|(_, _, _, western)| !western)
        .map(|(_, _, advance, _)| *advance)
        .fold(0.0f32, f32::max)
        .max(1.0);
    let flags: Vec<(bool, f32)> = glyphs
        .iter()
        .map(|(_, _, advance, western)| (*western, *advance))
        .collect();
    let (extents, placement) = ruby_pieces(&flags, slot);
    (slot, extents, placement)
}

/// Ink edge shared by the glyphs of one ruby line: the lowest ink bottom for
/// over ruby, the highest ink top for under ruby. One edge for the whole line
/// keeps marks whose ink sits mid-height, such as ー and ・, on the line's
/// baseline instead of pulling each of them onto the base.
pub(super) fn ruby_line_ink_edge(shaped: &[ShapedRun], glyphs: &[RubyGlyphRef], over: bool) -> f32 {
    let edges = glyphs.iter().map(|(run_index, glyph, _, _)| {
        let run = &shaped[*run_index];
        let (_, metrics) = run.font.metrics();
        let fallback = if over {
            metrics.descent
        } else {
            metrics.ascent
        };
        horizontal_ruby_ink_edge(&run.font, run.glyphs[*glyph], fallback, over)
    });
    let edge = if over {
        edges.fold(f32::MIN, f32::max)
    } else {
        edges.fold(f32::MAX, f32::min)
    };
    if edge.is_finite() && edge.abs() < f32::MAX {
        edge
    } else {
        0.0
    }
}

/// Paint ruby for one laid-out horizontal paragraph. Draw-only: base rects
/// come from `get_rects_for_range`, and the ruby is shaped at
/// `ruby_font_size` and spread over each line's rect with the vertical path's
/// jlreq distribution (`distribute_ruby_pieces`). Lines are not reflowed; ruby
/// draws in the line's leading.
pub(crate) fn paint_horizontal_ruby(
    canvas: &Canvas,
    text_content: &TextContent,
    paragraph_index: usize,
    laid_out: &skia::textlayout::Paragraph,
    x: f32,
    y: f32,
) {
    let Some(paragraph) = text_content.paragraphs().get(paragraph_index) else {
        return;
    };
    if !paragraph.children().iter().any(TextSpan::has_ruby) {
        return;
    }
    let font_provider = get_resources().fonts.font_provider();
    let fallback_mgr = FontMgr::from(font_provider.clone());
    let fallback_families: Vec<String> = get_fallback_fonts().iter().cloned().collect();
    let bounds = text_content.bounds();
    let plans = text_content.horizontal_plans();
    let Some(plan) = plans.get(paragraph_index) else {
        return;
    };
    let offsets = &plan.offsets;
    let lines = laid_out.get_line_metrics();

    for (span_index, span_range) in horizontal_ruby_targets(paragraph, offsets) {
        let span = &paragraph.children()[span_index];
        let paint = horizontal_ruby_paint(
            laid_out,
            &offsets.ranges[span_index],
            merge_fills(&span.fills, bounds),
        );
        let rects = laid_out.get_rects_for_range(
            span_range,
            skia::textlayout::RectHeightStyle::Tight,
            skia::textlayout::RectWidthStyle::Tight,
        );
        let shaped = shape_segment_with_fallbacks(
            span.ruby_text(),
            span.ruby_font_size(),
            &span_font_families(span, &fallback_families),
            font_provider,
            false,
            span.font_features,
            &fallback_mgr,
        );
        let glyphs = ruby_glyph_refs(span.ruby_text(), &shaped, span.ruby_font_size());
        let extents: Vec<f32> = rects.iter().map(|rect| rect.rect.width()).collect();
        let counts = split_counts_by_extent(&extents, glyphs.len());
        let mut assigned = 0usize;
        for (rect_box, count) in rects.iter().zip(counts) {
            let slice = &glyphs[assigned..assigned + count];
            assigned += count;
            if slice.is_empty() {
                continue;
            }
            let leading_gap = plan.ruby_spacing.leading_gaps[span_index];
            let room = room_inside_line(
                &lines,
                rect_box.rect,
                rect_box.rect.left() - leading_gap,
                rect_box.rect.width() + leading_gap,
                plan.ruby_spacing.rooms[span_index],
            );
            let ruby_line = RubyLine {
                span,
                shaped: &shaped,
                glyphs: slice,
                base: rect_box.rect,
                leading_gap,
                room,
            };
            ruby_line.paint(canvas, &paint, x, y);
        }
    }
}

/// The ruby glyphs over one line's rect of a base.
pub(super) struct RubyLine<'a> {
    pub span: &'a TextSpan,
    pub shaped: &'a [ShapedRun],
    pub glyphs: &'a [RubyGlyphRef],
    /// Base rect in the laid-out paragraph.
    pub base: skia::Rect,
    /// Spacing before the base, which sits on the previous character.
    pub leading_gap: f32,
    /// Overhang room (before, after) inside the line.
    pub room: (f32, f32),
}

impl RubyLine<'_> {
    /// Glyphs set in pieces (`horizontal_ruby_pieces`), distributed over the
    /// base and the spacing before it.
    fn paint(&self, canvas: &Canvas, paint: &skia::Paint, x: f32, y: f32) {
        let baseline = self.baseline(y);
        for ((run_index, glyph, _, _), left) in self.glyphs.iter().zip(self.glyph_lefts()) {
            let run = &self.shaped[*run_index];
            if let Some(blob) = single_glyph_blob(&run.font, run.glyphs[*glyph]) {
                canvas.draw_text_blob(&blob, (x + left, baseline), paint);
            }
        }
    }

    /// Left edge of each glyph: upright glyphs centred in their slot, the
    /// letters of a Western word at their own advances.
    pub(super) fn glyph_lefts(&self) -> Vec<f32> {
        let (slot, pieces, placement) = horizontal_ruby_pieces(self.glyphs);
        let piece_lefts = distribute_ruby_pieces(
            self.base.left() - self.leading_gap,
            self.base.width() + self.leading_gap,
            &pieces,
            self.span.ruby_align,
            self.room,
        );
        self.glyphs
            .iter()
            .zip(placement)
            .map(|((_, _, advance, western), (piece, offset))| {
                let centring = if *western {
                    0.0
                } else {
                    (slot - advance) / 2.0
                };
                piece_lefts[piece] + offset + centring
            })
            .collect()
    }

    /// Baseline of the line that attaches its ink to the base: above the base
    /// em for over ruby, below the base rect for under ruby.
    pub(super) fn baseline(&self, y: f32) -> f32 {
        let over = self.span.ruby_side == RubySide::Over;
        let edge = ruby_line_ink_edge(self.shaped, self.glyphs, over);
        if over {
            y + horizontal_annotation_over_top(self.base, self.span.font_size) - edge
        } else {
            y + self.base.bottom() - edge
        }
    }
}

/// Overhang room of a horizontal ruby base, without the sides at its line's
/// edges: ruby never sticks out past the first or last character.
fn room_inside_line(
    lines: &[skia::textlayout::LineMetrics],
    rect: skia::Rect,
    left: f32,
    width: f32,
    room: (f32, f32),
) -> (f32, f32) {
    let middle = rect.center_y();
    let Some(line) = lines.iter().find(|line| {
        let baseline = line.baseline as f32;
        middle >= baseline - line.ascent as f32 && middle <= baseline + line.descent as f32
    }) else {
        return room;
    };
    let line_left = line.left as f32;
    let line_right = line_left + line.width as f32;
    (
        if left <= line_left + 0.5 { 0.0 } else { room.0 },
        if left + width >= line_right - 0.5 {
            0.0
        } else {
            room.1
        },
    )
}
