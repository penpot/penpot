use super::*;

#[derive(Debug, Clone)]
pub(crate) struct HorizontalSpanRange {
    pub span: usize,
    pub builder_start: usize,
    pub builder_end: usize,
    pub shifted_start: usize,
    pub source_start: usize,
    pub source_end: usize,
    pub warichu: bool,
    pub style_anchor_start: usize,
}

/// Offset maps of one horizontal paragraph between source characters, the
/// kinsoku-adjusted layout text and the builder text. Building it runs the
/// kinsoku pass; reuse it for every lookup in the paragraph.
pub(crate) struct HorizontalOffsets {
    /// Map between the transformed text and the kinsoku-adjusted text.
    pub(crate) offset_map: kinsoku::OffsetMap,
    pub(crate) ranges: Vec<HorizontalSpanRange>,
    /// Transformed-text UTF-16 offset of every source character boundary.
    boundaries: Vec<usize>,
}

impl HorizontalOffsets {
    pub(crate) fn new(paragraph: &Paragraph) -> Self {
        let (span_texts, offset_map) = paragraph.layout_span_texts();
        Self::from_layout_texts(paragraph, &span_texts, offset_map)
    }

    /// Offsets for layout `span_texts` already built by `layout_span_texts`.
    pub(super) fn from_layout_texts(
        paragraph: &Paragraph,
        span_texts: &[String],
        offset_map: kinsoku::OffsetMap,
    ) -> Self {
        let ranges = span_ranges(paragraph, span_texts, &offset_map);
        Self {
            offset_map,
            ranges,
            boundaries: source_char_boundaries(paragraph),
        }
    }

    /// Builder-text UTF-16 offset of a paragraph source character offset.
    pub(crate) fn source_to_builder(&self, source_char_offset: usize) -> usize {
        let boundaries = &self.boundaries;
        let source_utf16 = boundaries
            .get(source_char_offset)
            .copied()
            .unwrap_or_else(|| boundaries.last().copied().unwrap_or(0));
        let Some(range) = self
            .ranges
            .iter()
            .find(|range| source_utf16 >= range.source_start && source_utf16 <= range.source_end)
        else {
            return self
                .ranges
                .last()
                .map(|range| range.builder_end)
                .unwrap_or(0);
        };
        if range.warichu {
            return if source_utf16 >= range.source_end {
                range.builder_end
            } else {
                range.builder_start
            };
        }
        let shifted = self.offset_map.to_shifted(source_utf16);
        range.builder_start + shifted.saturating_sub(range.shifted_start)
    }

    /// Paragraph source character offset of a builder-text UTF-16 offset.
    pub(crate) fn builder_to_source(&self, builder_offset: usize) -> usize {
        let source_utf16 = self
            .ranges
            .iter()
            .find(|range| {
                builder_offset >= range.builder_start && builder_offset <= range.builder_end
            })
            .map(|range| {
                if range.warichu {
                    if builder_offset > range.builder_start {
                        range.source_end
                    } else {
                        range.source_start
                    }
                } else {
                    let within = builder_offset
                        .saturating_sub(range.builder_start)
                        .min(range.builder_end - range.builder_start);
                    self.offset_map.to_original(range.shifted_start + within)
                }
            })
            .unwrap_or_else(|| {
                self.ranges
                    .last()
                    .map(|range| range.source_end)
                    .unwrap_or(0)
            });
        let boundaries = &self.boundaries;
        boundaries
            .partition_point(|boundary| *boundary < source_utf16)
            .min(boundaries.len().saturating_sub(1))
    }

    /// Builder-text ranges of the selected source characters
    /// `[source_start, source_end)` outside warichu spans.
    pub(crate) fn normal_selection_ranges(
        &self,
        paragraph: &Paragraph,
        source_start: usize,
        source_end: usize,
    ) -> Vec<std::ops::Range<usize>> {
        let mut span_start = 0usize;
        paragraph
            .children()
            .iter()
            .filter_map(|span| {
                let span_end = span_start + span.text.chars().count();
                let selected_start = source_start.max(span_start);
                let selected_end = source_end.min(span_end);
                span_start = span_end;
                if span.is_warichu() || selected_start >= selected_end {
                    return None;
                }
                Some(self.source_to_builder(selected_start)..self.source_to_builder(selected_end))
            })
            .collect()
    }
}

#[cfg(test)]
pub(crate) fn horizontal_span_ranges(paragraph: &Paragraph) -> Vec<HorizontalSpanRange> {
    HorizontalOffsets::new(paragraph).ranges
}

#[cfg(test)]
pub(crate) fn horizontal_builder_to_source(paragraph: &Paragraph, builder_offset: usize) -> usize {
    HorizontalOffsets::new(paragraph).builder_to_source(builder_offset)
}

/// Ranges shared by layout, position data and editor mapping. `shifted_*`
/// addresses the kinsoku-adjusted text; `builder_*` addresses the builder
/// text, where each warichu span collapses to one placeholder.
fn span_ranges(
    paragraph: &Paragraph,
    span_texts: &[String],
    offset_map: &kinsoku::OffsetMap,
) -> Vec<HorizontalSpanRange> {
    let mut builder_cursor = 0usize;
    let mut builder_byte_cursor = 0usize;
    let mut shifted_cursor = 0usize;
    paragraph
        .children()
        .iter()
        .zip(span_texts)
        .enumerate()
        .map(|(span_index, (span, text))| {
            let shifted_start = shifted_cursor;
            shifted_cursor += text.encode_utf16().count();
            let shifted_end = shifted_cursor;
            let source_start = offset_map.to_original(shifted_start);
            let source_end = offset_map.to_original(shifted_end);
            let warichu = span.is_warichu();
            let builder_start = builder_cursor;
            let style_anchor_start = if warichu {
                builder_byte_cursor + '\u{FFFC}'.len_utf8()
            } else {
                builder_byte_cursor
            };
            builder_cursor += if warichu {
                HORIZONTAL_WARICHU_BUILDER_LEN
            } else {
                shifted_end - shifted_start
            };
            builder_byte_cursor += if warichu {
                '\u{FFFC}'.len_utf8()
                    + HORIZONTAL_WARICHU_STYLE_ANCHOR.len_utf8()
                    + HORIZONTAL_WARICHU_BREAK_ANCHOR.len_utf8()
            } else {
                // `add_text_with_tabs` stores every tab as a U+FFFC placeholder.
                text.len() + text.matches('\t').count() * ('\u{FFFC}'.len_utf8() - 1)
            };
            HorizontalSpanRange {
                span: span_index,
                builder_start,
                builder_end: builder_cursor,
                shifted_start,
                source_start,
                source_end,
                warichu,
                style_anchor_start,
            }
        })
        .collect()
}

/// Transformed-text UTF-16 offset of every source character boundary: the
/// space the kinsoku offset map reports as "original". Differs from the
/// source UTF-16 when a transform changes the text length (`ß` -> `SS`).
fn source_char_boundaries(paragraph: &Paragraph) -> Vec<usize> {
    let mut boundaries = vec![0usize];
    let mut span_base = 0usize;
    for span in paragraph.children() {
        let applied = span.apply_text_transform_with_source_ranges();
        let mut ranges = applied.source_ranges.iter().peekable();
        let mut source_utf16 = 0usize;
        let mut output_end = 0usize;
        for character in span.text.chars() {
            source_utf16 += character.len_utf16();
            while let Some((output, _)) = ranges.next_if(|(_, source)| source.end <= source_utf16) {
                output_end = output.end;
            }
            boundaries.push(span_base + output_end);
        }
        span_base += applied.text.encode_utf16().count();
    }
    boundaries
}
