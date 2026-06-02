use crate::render::text::calculate_decoration_metrics;
use crate::{
    math::{Bounds, Matrix, Rect},
    render::{default_font, DEFAULT_EMOJI_FONT},
    utils::Browser,
};

use core::f32;
use macros::ToJs;
use skia_safe::textlayout::{RectHeightStyle, RectWidthStyle};
use skia_safe::{
    self as skia,
    paint::{self, Paint},
    textlayout::ParagraphBuilder,
    textlayout::ParagraphStyle,
    textlayout::PositionWithAffinity,
    Contains,
};

use std::collections::HashSet;

use super::FontFamily;
use crate::math::Point;
use crate::shapes::{self, merge_fills, Shape, VerticalAlign};
use crate::utils::{get_fallback_fonts, get_font_collection};
use crate::Uuid;
use crate::STATE;

// TODO: maybe move this to the wasm module?
pub type ParagraphBuilderGroup = Vec<ParagraphBuilder>;

#[repr(u8)]
#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
pub enum GrowType {
    Fixed = 0,
    AutoWidth = 1,
    AutoHeight = 2,
}

#[derive(Debug, PartialEq, Copy, Clone)]
pub struct TextContentSize {
    pub width: f32,
    pub height: f32,
    pub max_width: f32,
    pub normalized_line_height: f32,
}

const DEFAULT_TEXT_CONTENT_SIZE: f32 = 0.01;

impl TextContentSize {
    pub fn default() -> Self {
        Self {
            width: DEFAULT_TEXT_CONTENT_SIZE,
            height: DEFAULT_TEXT_CONTENT_SIZE,
            max_width: DEFAULT_TEXT_CONTENT_SIZE,
            normalized_line_height: 0.0,
        }
    }

    pub fn new_with_size(width: f32, height: f32) -> Self {
        Self {
            width,
            height,
            max_width: DEFAULT_TEXT_CONTENT_SIZE,
            normalized_line_height: 0.0,
        }
    }

    pub fn new_with_normalized_line_height(
        width: f32,
        height: f32,
        max_width: f32,
        normalized_line_height: f32,
    ) -> Self {
        Self {
            width,
            height,
            max_width,
            normalized_line_height,
        }
    }

    pub fn set_size(&mut self, width: f32, height: f32) {
        self.width = width;
        self.height = height;
    }

    pub fn copy_finite_size(
        &mut self,
        size: TextContentSize,
        default_height: f32,
        default_width: f32,
    ) {
        if f32::is_finite(size.width) {
            self.width = size.width;
        } else {
            self.width = default_width;
        }
        if f32::is_finite(size.max_width) {
            self.max_width = size.max_width;
        } else {
            self.max_width = default_width
        }
        if f32::is_finite(size.height) {
            self.height = size.height;
        } else {
            self.height = default_height;
        }
        if f32::is_finite(size.normalized_line_height) {
            self.normalized_line_height = size.normalized_line_height;
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct TextPositionWithAffinity {
    pub position_with_affinity: PositionWithAffinity,
    pub paragraph: i32,
    #[allow(dead_code)]
    pub span: i32,
    pub offset: i32,
}

impl TextPositionWithAffinity {
    pub fn new(
        position_with_affinity: PositionWithAffinity,
        paragraph: i32,
        span: i32,
        offset: i32,
    ) -> Self {
        Self {
            position_with_affinity,
            paragraph,
            span,
            offset,
        }
    }
}

#[derive(Debug)]
pub struct TextContentLayoutResult(
    Vec<ParagraphBuilderGroup>,
    Vec<Vec<skia::textlayout::Paragraph>>,
    TextContentSize,
);

#[derive(Debug)]
pub struct TextContentLayout {
    pub paragraph_builders: Vec<ParagraphBuilderGroup>,
    pub paragraphs: Vec<Vec<skia::textlayout::Paragraph>>,
}

impl Clone for TextContentLayout {
    fn clone(&self) -> Self {
        Self {
            paragraph_builders: vec![],
            paragraphs: vec![],
        }
    }
}

impl PartialEq for TextContentLayout {
    fn eq(&self, _other: &Self) -> bool {
        true
    }
}

impl TextContentLayout {
    pub fn new() -> Self {
        Self {
            paragraph_builders: vec![],
            paragraphs: vec![],
        }
    }

    pub fn set(
        &mut self,
        paragraph_builders: Vec<ParagraphBuilderGroup>,
        paragraphs: Vec<Vec<skia::textlayout::Paragraph>>,
    ) {
        self.paragraph_builders = paragraph_builders;
        self.paragraphs = paragraphs;
    }

    pub fn needs_update(&self) -> bool {
        self.paragraph_builders.is_empty() || self.paragraphs.is_empty()
    }
}

#[allow(dead_code)]
#[derive(Debug, Clone)]
pub struct TextDecorationSegment {
    pub kind: skia::textlayout::TextDecoration,
    pub text_style: skia::textlayout::TextStyle,
    pub y: f32,
    pub thickness: f32,
    pub left: f32,
    pub width: f32,
}

/*
 * Check if the current x,y (in paragraph relative coordinates) is inside
 * the paragraph
 */
#[allow(dead_code)]
fn intersects(paragraph: &skia_safe::textlayout::Paragraph, x: f32, y: f32) -> bool {
    if y < 0.0 || y > paragraph.height() {
        return false;
    }

    let pos = paragraph.get_glyph_position_at_coordinate((x, y));
    let idx = pos.position as usize;

    let rects =
        paragraph.get_rects_for_range(0..idx + 1, RectHeightStyle::Tight, RectWidthStyle::Tight);

    rects.iter().any(|r| r.rect.contains(&Point::new(x, y)))
}

// Performs a text auto layout without width limits.
// This should be the same as text_auto_layout.
pub fn build_paragraphs_from_paragraph_builders(
    paragraph_builders: &mut [ParagraphBuilderGroup],
    width: f32,
) -> Vec<Vec<skia::textlayout::Paragraph>> {
    let paragraphs = paragraph_builders
        .iter_mut()
        .map(|builders| {
            builders
                .iter_mut()
                .map(|builder| {
                    let mut paragraph = builder.build();
                    // For auto-width, always layout with infinite width first to get intrinsic width
                    paragraph.layout(width);
                    paragraph
                })
                .collect()
        })
        .collect();
    paragraphs
}

/// Calculate the normalized line height from paragraph builders
pub fn calculate_normalized_line_height(
    paragraph_builders: &mut [ParagraphBuilderGroup],
    width: f32,
) -> f32 {
    let mut normalized_line_height = 0.0;
    for paragraph_builder_group in paragraph_builders.iter_mut() {
        for paragraph_builder in paragraph_builder_group.iter_mut() {
            let mut paragraph = paragraph_builder.build();
            paragraph.layout(width);
            let baseline = paragraph.ideographic_baseline();
            if baseline > normalized_line_height {
                normalized_line_height = baseline;
            }
        }
    }
    normalized_line_height
}

#[derive(Debug, PartialEq, Clone)]
pub struct TextContent {
    pub paragraphs: Vec<Paragraph>,
    pub bounds: Rect,
    pub grow_type: GrowType,
    pub size: TextContentSize,
    pub layout: TextContentLayout,
}

impl TextContent {
    pub fn new(bounds: Rect, grow_type: GrowType) -> Self {
        Self {
            paragraphs: Vec::new(),
            bounds,
            grow_type,
            size: TextContentSize::default(),
            layout: TextContentLayout::new(),
        }
    }

    pub fn new_bounds(&self, bounds: Rect) -> Self {
        let paragraphs = self.paragraphs.clone();
        let grow_type = self.grow_type;
        Self {
            paragraphs,
            bounds,
            grow_type,
            size: TextContentSize::new_with_size(bounds.width(), bounds.height()),
            layout: TextContentLayout::new(),
        }
    }

    pub fn bounds(&self) -> Rect {
        self.bounds
    }

    pub fn set_xywh(&mut self, x: f32, y: f32, w: f32, h: f32) {
        self.bounds = Rect::from_xywh(x, y, w, h);
    }

    #[allow(dead_code)]
    pub fn x(&self) -> f32 {
        self.bounds.x()
    }

    #[allow(dead_code)]
    pub fn y(&self) -> f32 {
        self.bounds.y()
    }

    pub fn add_paragraph(&mut self, paragraph: Paragraph) {
        self.paragraphs.push(paragraph);
    }

    pub fn paragraphs(&self) -> &[Paragraph] {
        &self.paragraphs
    }

    pub fn paragraphs_mut(&mut self) -> &mut Vec<Paragraph> {
        &mut self.paragraphs
    }

    pub fn width(&self) -> f32 {
        self.size.width
    }

    pub fn normalized_line_height(&self) -> f32 {
        self.size.normalized_line_height
    }

    pub fn grow_type(&self) -> GrowType {
        self.grow_type
    }

    pub fn set_grow_type(&mut self, grow_type: GrowType) {
        self.grow_type = grow_type;
    }

    pub fn calculate_bounds(&self, shape: &Shape, apply_transform: bool) -> Bounds {
        let (x, mut y, transform, center) = (
            shape.selrect.x(),
            shape.selrect.y(),
            &shape.transform,
            &shape.center(),
        );

        let width = if self.grow_type() == GrowType::AutoWidth {
            self.size.width
        } else {
            shape.selrect().width()
        };

        let height = if self.size.width.round() != width.round() {
            self.get_height(width)
        } else {
            self.size.height
        };

        let offset_y = match shape.vertical_align() {
            VerticalAlign::Center => (shape.selrect().height() - height) / 2.0,
            VerticalAlign::Bottom => shape.selrect().height() - height,
            _ => 0.0,
        };
        y += offset_y;

        let text_rect = Rect::from_xywh(x, y, width, height);
        let mut bounds = Bounds::new(
            Point::new(text_rect.x(), text_rect.y()),
            Point::new(text_rect.x() + text_rect.width(), text_rect.y()),
            Point::new(
                text_rect.x() + text_rect.width(),
                text_rect.y() + text_rect.height(),
            ),
            Point::new(text_rect.x(), text_rect.y() + text_rect.height()),
        );

        if apply_transform && !transform.is_identity() {
            let mut matrix = *transform;
            matrix.post_translate(*center);
            matrix.pre_translate(-*center);
            bounds.transform_mut(&matrix);
        }

        bounds
    }

    pub fn content_rect(&self, selrect: &Rect, valign: VerticalAlign) -> Rect {
        let x = selrect.x();
        let mut y = selrect.y();

        let width = if self.grow_type() == GrowType::AutoWidth {
            self.size.width
        } else {
            selrect.width()
        };

        let height = if self.size.width.round() != width.round() {
            self.get_height(width)
        } else {
            self.size.height
        };

        let offset_y = match valign {
            VerticalAlign::Center => (selrect.height() - height) / 2.0,
            VerticalAlign::Bottom => selrect.height() - height,
            _ => 0.0,
        };
        y += offset_y;

        Rect::from_xywh(x, y, width, height)
    }

    pub fn transform(&mut self, transform: &Matrix) {
        let left = self.bounds.left();
        let right = self.bounds.right();
        let top = self.bounds.top();
        let bottom = self.bounds.bottom();
        let p1 = transform.map_point(skia::Point::new(left, top));
        let p2 = transform.map_point(skia::Point::new(right, bottom));
        self.bounds = Rect::from_ltrb(p1.x, p1.y, p2.x, p2.y);
    }

    pub fn get_caret_position_at(&self, point: &Point) -> Option<TextPositionWithAffinity> {
        let mut offset_y = 0.0;
        let layout_paragraphs = self.layout.paragraphs.iter().flatten();

        let mut paragraph_index: i32 = -1;
        let mut span_index: i32 = -1;
        for layout_paragraph in layout_paragraphs {
            paragraph_index += 1;
            let start_y = offset_y;
            let end_y = offset_y + layout_paragraph.height();

            // We only test against paragraphs that can contain the current y
            // coordinate. Use >= for start and handle zero-height paragraphs.
            let paragraph_height = layout_paragraph.height();
            let matches = if paragraph_height > 0.0 {
                point.y >= start_y && point.y < end_y
            } else {
                // For zero-height paragraphs (empty lines), match if we're at the start position
                point.y >= start_y && point.y <= start_y + 1.0
            };

            if matches {
                let position_with_affinity =
                    layout_paragraph.get_glyph_position_at_coordinate(*point);
                if let Some(paragraph) = self.paragraphs().get(paragraph_index as usize) {
                    // Computed position keeps the current position in terms
                    // of number of characters of text. This is used to know
                    // in which span we are.
                    let mut computed_position = 0;
                    let mut span_offset = 0;

                    // If paragraph has no spans, default to span 0, offset 0
                    if paragraph.children().is_empty() {
                        span_index = 0;
                        span_offset = 0;
                    } else {
                        for span in paragraph.children() {
                            span_index += 1;
                            let length = span.text.chars().count();
                            let start_position = computed_position;
                            let end_position = computed_position + length;
                            let current_position = position_with_affinity.position as usize;

                            // Handle empty spans: if the span is empty and current position
                            // matches the start, this is the right span
                            if length == 0 && current_position == start_position {
                                span_offset = 0;
                                break;
                            }

                            if start_position <= current_position
                                && end_position >= current_position
                            {
                                span_offset =
                                    position_with_affinity.position - start_position as i32;
                                break;
                            }
                            computed_position += length;
                        }
                    }

                    return Some(TextPositionWithAffinity::new(
                        position_with_affinity,
                        paragraph_index,
                        span_index,
                        span_offset,
                    ));
                }
            }
            offset_y += layout_paragraph.height();
        }

        // Handle completely empty text shapes: if there are no paragraphs or all paragraphs
        // are empty, and the click is within the text shape bounds, return a default position
        if (self.paragraphs().is_empty() || self.layout.paragraphs.is_empty())
            && self.bounds.contains(*point)
        {
            // Create a default position at the start of the text
            use skia_safe::textlayout::Affinity;
            let default_position = PositionWithAffinity {
                position: 0,
                affinity: Affinity::Downstream,
            };
            return Some(TextPositionWithAffinity::new(
                default_position,
                0, // paragraph 0
                0, // span 0
                0, // offset 0
            ));
        }

        None
    }

    /// Builds the ParagraphBuilders necessary to render
    /// this text.
    pub fn paragraph_builder_group_from_text(
        &self,
        use_shadow: Option<bool>,
    ) -> Vec<ParagraphBuilderGroup> {
        let fonts = get_font_collection();
        let fallback_fonts = get_fallback_fonts();
        let mut paragraph_group = Vec::new();

        for paragraph in self.paragraphs() {
            let paragraph_style = paragraph.paragraph_to_style();
            let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
            for span in paragraph.children() {
                let remove_alpha = use_shadow.unwrap_or(false) && !span.is_transparent();
                let text_style = span.to_style(
                    &self.bounds(),
                    fallback_fonts,
                    remove_alpha,
                    paragraph.line_height(),
                );
                let text: String = span.apply_text_transform();
                builder.push_style(&text_style);
                builder.add_text(&text);
            }
            paragraph_group.push(vec![builder]);
        }

        paragraph_group
    }

    /// Performs an Auto Width text layout.
    fn text_layout_auto_width(&self) -> TextContentLayoutResult {
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);

        let normalized_line_height =
            calculate_normalized_line_height(&mut paragraph_builders, f32::MAX);

        let paragraphs =
            build_paragraphs_from_paragraph_builders(&mut paragraph_builders, f32::MAX);

        let (width, height) =
            paragraphs
                .iter()
                .flatten()
                .fold((0.0, 0.0), |(auto_width, auto_height), paragraph| {
                    (
                        f32::max(paragraph.longest_line(), auto_width),
                        auto_height + paragraph.height(),
                    )
                });

        let size = TextContentSize::new_with_normalized_line_height(
            width.ceil(),
            height.ceil(),
            width.ceil(),
            normalized_line_height,
        );
        TextContentLayoutResult(paragraph_builders, paragraphs, size)
    }

    /// Private function that performs
    /// Performs an Auto Height text layout.
    fn text_layout_auto_height(&self) -> TextContentLayoutResult {
        let width = self.width();
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);

        let normalized_line_height =
            calculate_normalized_line_height(&mut paragraph_builders, width);

        let paragraphs = build_paragraphs_from_paragraph_builders(&mut paragraph_builders, width);
        let height = paragraphs
            .iter()
            .flatten()
            .fold(0.0, |auto_height, paragraph| {
                auto_height + paragraph.height()
            });
        let size = TextContentSize::new_with_normalized_line_height(
            width,
            height.ceil(),
            DEFAULT_TEXT_CONTENT_SIZE,
            normalized_line_height,
        );
        TextContentLayoutResult(paragraph_builders, paragraphs, size)
    }

    /// Performs a Fixed text layout.
    fn text_layout_fixed(&self) -> TextContentLayoutResult {
        let width = self.width();
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);

        let normalized_line_height =
            calculate_normalized_line_height(&mut paragraph_builders, width);

        let paragraphs = build_paragraphs_from_paragraph_builders(&mut paragraph_builders, width);
        let paragraph_height = paragraphs
            .iter()
            .flatten()
            .fold(0.0, |auto_height, paragraph| {
                auto_height + paragraph.height()
            });

        let size = TextContentSize::new_with_normalized_line_height(
            width,
            paragraph_height.ceil(),
            DEFAULT_TEXT_CONTENT_SIZE,
            normalized_line_height,
        );
        TextContentLayoutResult(paragraph_builders, paragraphs, size)
    }

    pub fn get_width(&self, width: f32) -> f32 {
        if self.grow_type() == GrowType::AutoWidth {
            self.size.width
        } else {
            width
        }
    }

    pub fn get_height(&self, width: f32) -> f32 {
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);
        let paragraphs = build_paragraphs_from_paragraph_builders(&mut paragraph_builders, width);
        let paragraph_height = paragraphs
            .iter()
            .flatten()
            .fold(0.0, |auto_height, paragraph| {
                auto_height + paragraph.height()
            });
        paragraph_height
    }

    pub fn needs_update_layout(&self) -> bool {
        self.layout.needs_update()
    }

    pub fn set_layout_from_result(
        &mut self,
        result: TextContentLayoutResult,
        default_height: f32,
        default_width: f32,
    ) {
        self.layout.set(result.0, result.1);
        self.size
            .copy_finite_size(result.2, default_height, default_width);
    }

    pub fn update_layout(&mut self, selrect: Rect) -> TextContentSize {
        self.size.set_size(selrect.width(), selrect.height());

        match self.grow_type() {
            GrowType::AutoHeight => {
                let result = self.text_layout_auto_height();
                self.set_layout_from_result(result, selrect.width(), selrect.height());
            }
            GrowType::AutoWidth => {
                let result = self.text_layout_auto_width();
                self.set_layout_from_result(result, selrect.width(), selrect.height());
            }
            GrowType::Fixed => {
                let result = self.text_layout_fixed();
                self.set_layout_from_result(result, selrect.width(), selrect.height());
            }
        }

        if self.is_empty() {
            let (placeholder_width, placeholder_height) = self.placeholder_dimensions(selrect);
            self.size.width = placeholder_width;
            self.size.height = placeholder_height;
            self.size.max_width = placeholder_width;
        }

        self.size
    }

    /// Return true when the content represents a freshly created empty text.
    /// We consider it empty only if there is exactly one paragraph with a single
    /// span whose text buffer is empty. Any additional paragraphs or characters
    /// mean the user has already entered content.
    fn is_empty(&self) -> bool {
        if self.paragraphs.len() != 1 {
            return false;
        }

        let paragraph = match self.paragraphs.first() {
            Some(paragraph) => paragraph,
            None => return true,
        };
        if paragraph.children().len() != 1 {
            return false;
        }

        let span = match paragraph.children().first() {
            Some(span) => span,
            None => return true,
        };

        span.text.is_empty()
    }

    /// Compute the placeholder size used while the text is still empty. We ask
    /// Skia to measure a single glyph using the span's typography so the editor
    /// shows a caret-sized box that reflects the selected font, size and spacing.
    /// If that fails we fall back to the previous WASM size or the incoming
    /// selrect dimensions.
    fn placeholder_dimensions(&self, selrect: Rect) -> (f32, f32) {
        if let Some(paragraph) = self.paragraphs.first() {
            if let Some(span) = paragraph.children().first() {
                let fonts = get_font_collection();
                let fallback_fonts = get_fallback_fonts();
                let paragraph_style = paragraph.paragraph_to_style();
                let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);

                let text_style = span.to_style(
                    &self.bounds(),
                    fallback_fonts,
                    false,
                    paragraph.line_height(),
                );

                builder.push_style(&text_style);
                builder.add_text("0");

                let mut paragraph_layout = builder.build();
                paragraph_layout.layout(f32::MAX);

                let width = paragraph_layout.max_intrinsic_width();
                let height = paragraph_layout.height();

                return (width, height);
            }
        }

        let fallback_width = selrect.width().max(self.size.width);
        let fallback_height = selrect.height().max(self.size.height);

        (fallback_width, fallback_height)
    }

    #[allow(dead_code)]
    pub fn intersect_position_in_shape(&self, shape: &Shape, x_pos: f32, y_pos: f32) -> bool {
        let rect = shape.selrect;
        let mut matrix = Matrix::new_identity();
        let center = shape.center();
        let Some(inv_transform) = &shape.transform.invert() else {
            return false;
        };
        matrix.pre_translate(center);
        matrix.pre_concat(inv_transform);
        matrix.pre_translate(-center);

        let result = matrix.map_point((x_pos, y_pos));

        let x_pos = result.x;
        let y_pos = result.y;

        x_pos >= rect.x() && x_pos <= rect.right() && y_pos >= rect.y() && y_pos <= rect.bottom()
    }

    pub fn intersect_position_in_text(&self, shape: &Shape, x_pos: f32, y_pos: f32) -> bool {
        let rect = self.content_rect(&shape.selrect, shape.vertical_align);
        let mut matrix = Matrix::new_identity();
        let center = shape.center();
        let Some(inv_transform) = &shape.transform.invert() else {
            return false;
        };
        matrix.pre_translate(center);
        matrix.pre_concat(inv_transform);
        matrix.pre_translate(-center);

        let result = matrix.map_point((x_pos, y_pos));

        // Change coords to content space
        let x_pos = result.x - rect.x();
        let y_pos = result.y - rect.y();

        let width = self.width();
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);
        let paragraphs = build_paragraphs_from_paragraph_builders(&mut paragraph_builders, width);

        paragraphs
            .iter()
            .flatten()
            .scan(
                (0 as f32, None::<skia::textlayout::Paragraph>),
                |(height, _), p| {
                    let prev_height = *height;
                    *height += p.height();
                    Some((prev_height, p))
                },
            )
            .any(|(height, p)| intersects(p, x_pos, y_pos - height))
    }
}

impl Default for TextContent {
    fn default() -> Self {
        Self {
            paragraphs: vec![],
            bounds: Rect::default(),
            grow_type: GrowType::Fixed,
            size: TextContentSize::default(),
            layout: TextContentLayout::new(),
        }
    }
}

pub type TextAlign = skia::textlayout::TextAlign;
pub type TextDirection = skia::textlayout::TextDirection;
pub type TextDecoration = skia::textlayout::TextDecoration;

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum TextTransform {
    Lowercase,
    Uppercase,
    Capitalize,
}

// FIXME: Rethink this type. We'll probably need to move the serialization to the
// wasm module and store here meaningful model values (and/or skia type aliases)
#[derive(Debug, PartialEq, Clone)]
pub struct Paragraph {
    text_align: TextAlign,
    text_direction: TextDirection,
    text_decoration: Option<TextDecoration>,
    text_transform: Option<TextTransform>,
    line_height: f32,
    letter_spacing: f32,
    children: Vec<TextSpan>,
}

impl Default for Paragraph {
    fn default() -> Self {
        Self {
            text_align: TextAlign::default(),
            text_direction: TextDirection::LTR,
            text_decoration: None,
            text_transform: None,
            line_height: 1.0,
            letter_spacing: 0.0,
            children: vec![],
        }
    }
}

impl Paragraph {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        text_align: TextAlign,
        text_direction: TextDirection,
        text_decoration: Option<TextDecoration>,
        text_transform: Option<TextTransform>,
        line_height: f32,
        letter_spacing: f32,
        children: Vec<TextSpan>,
    ) -> Self {
        Self {
            text_align,
            text_direction,
            text_decoration,
            text_transform,
            line_height,
            letter_spacing,
            children,
        }
    }

    #[allow(dead_code)]
    fn set_children(&mut self, children: Vec<TextSpan>) {
        self.children = children;
    }

    pub fn children(&self) -> &[TextSpan] {
        &self.children
    }

    pub fn children_mut(&mut self) -> &mut Vec<TextSpan> {
        &mut self.children
    }

    #[allow(dead_code)]
    fn add_span(&mut self, span: TextSpan) {
        self.children.push(span);
    }

    pub fn line_height(&self) -> f32 {
        self.line_height
    }

    pub fn letter_spacing(&self) -> f32 {
        self.letter_spacing
    }

    pub fn text_align(&self) -> TextAlign {
        self.text_align
    }

    pub fn text_direction(&self) -> TextDirection {
        self.text_direction
    }

    pub fn text_decoration(&self) -> Option<TextDecoration> {
        self.text_decoration
    }

    pub fn text_transform(&self) -> Option<TextTransform> {
        self.text_transform
    }

    pub fn paragraph_to_style(&self) -> ParagraphStyle {
        let mut style = ParagraphStyle::default();

        style.set_height(self.line_height);
        style.set_text_align(self.text_align);
        style.set_text_direction(self.text_direction);
        style.set_replace_tab_characters(true);
        style.set_apply_rounding_hack(true);
        style.set_text_height_behavior(skia::textlayout::TextHeightBehavior::All);
        style
    }

    pub fn scale_content(&mut self, value: f32) {
        self.letter_spacing *= value;
        self.children
            .iter_mut()
            .for_each(|l| l.scale_content(value));
    }
}

#[derive(Debug, PartialEq, Clone)]
pub struct TextSpan {
    pub text: String,
    pub font_family: FontFamily,
    pub font_size: f32,
    pub line_height: f32,
    pub letter_spacing: f32,
    pub font_weight: i32,
    pub font_variant_id: Uuid,
    pub text_decoration: Option<TextDecoration>,
    pub text_transform: Option<TextTransform>,
    pub text_direction: TextDirection,
    pub fills: Vec<shapes::Fill>,
}

impl TextSpan {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        text: String,
        font_family: FontFamily,
        font_size: f32,
        line_height: f32,
        letter_spacing: f32,
        text_decoration: Option<TextDecoration>,
        text_transform: Option<TextTransform>,
        text_direction: TextDirection,
        font_weight: i32,
        font_variant_id: Uuid,
        fills: Vec<shapes::Fill>,
    ) -> Self {
        Self {
            text,
            font_family,
            font_size,
            line_height,
            letter_spacing,
            text_decoration,
            text_transform,
            text_direction,
            font_weight,
            font_variant_id,
            fills,
        }
    }

    pub fn set_text(&mut self, text: String) {
        self.text = text;
    }

    pub fn fills(&self) -> &[shapes::Fill] {
        &self.fills
    }

    pub fn to_style(
        &self,
        content_bounds: &Rect,
        fallback_fonts: &HashSet<String>,
        remove_alpha: bool,
        paragraph_line_height: f32,
    ) -> skia::textlayout::TextStyle {
        let mut style = skia::textlayout::TextStyle::default();
        let mut paint = paint::Paint::default();

        if remove_alpha {
            paint.set_color(skia::Color::BLACK);
            paint.set_alpha(255);
        } else {
            paint = merge_fills(&self.fills, *content_bounds);
        }

        let max_line_height = f32::max(paragraph_line_height, self.line_height);
        style.set_height(max_line_height);
        style.set_height_override(true);
        style.set_foreground_paint(&paint);
        style.set_decoration_type(match self.text_decoration {
            Some(text_decoration) => text_decoration,
            None => skia::textlayout::TextDecoration::NO_DECORATION,
        });

        // Trick to avoid showing the text decoration
        style.set_decoration_thickness_multiplier(0.0);

        let mut font_families = vec![
            self.serialized_font_family(),
            default_font(),
            DEFAULT_EMOJI_FONT.to_string(),
        ];

        font_families.extend(fallback_fonts.iter().cloned());
        style.set_font_families(&font_families);
        style.set_font_size(self.font_size);
        style.set_letter_spacing(self.letter_spacing);
        style.set_half_leading(true);

        style
    }

    pub fn to_stroke_style(
        &self,
        stroke_paint: &Paint,
        fallback_fonts: &HashSet<String>,
        remove_alpha: bool,
        paragraph_line_height: f32,
    ) -> skia::textlayout::TextStyle {
        let mut style = self.to_style(
            &Rect::default(),
            fallback_fonts,
            remove_alpha,
            paragraph_line_height,
        );
        if remove_alpha {
            let mut paint = skia::Paint::default();
            paint.set_style(stroke_paint.style());
            paint.set_stroke_width(stroke_paint.stroke_width());
            paint.set_color(skia::Color::BLACK);
            paint.set_alpha(255);
            style.set_foreground_paint(&paint);
        } else {
            style.set_foreground_paint(stroke_paint);
        }

        style.set_font_size(self.font_size);
        style.set_letter_spacing(self.letter_spacing);
        style.set_decoration_type(match self.text_decoration {
            Some(text_decoration) => text_decoration,
            None => skia::textlayout::TextDecoration::NO_DECORATION,
        });
        style
    }

    fn serialized_font_family(&self) -> String {
        format!("{}", self.font_family)
    }

    fn process_ignored_chars(text: &str, browser: u8) -> String {
        text.chars()
            .filter_map(|c| {
                if c < '\u{0020}' || c == '\u{2028}' || c == '\u{2029}' {
                    if browser == Browser::Firefox as u8 {
                        None
                    } else {
                        Some(' ')
                    }
                } else {
                    Some(c)
                }
            })
            .collect()
    }

    pub fn apply_text_transform(&self) -> String {
        let browser = crate::with_state!(state, { state.current_browser });
        let text = Self::process_ignored_chars(&self.text, browser);
        let transformed_text = match self.text_transform {
            Some(TextTransform::Uppercase) => text.to_uppercase(),
            Some(TextTransform::Lowercase) => text.to_lowercase(),
            Some(TextTransform::Capitalize) => text
                .split_whitespace()
                .map(|word| {
                    let mut chars = word.chars();
                    match chars.next() {
                        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
                        None => String::new(),
                    }
                })
                .collect::<Vec<_>>()
                .join(" "),
            None => text,
        };

        transformed_text.replace("/", "/\u{200B}")
    }

    pub fn scale_content(&mut self, value: f32) {
        self.font_size *= value;
    }

    pub fn is_transparent(&self) -> bool {
        self.fills.iter().all(|fill| match fill {
            shapes::Fill::Solid(shapes::SolidColor(color)) => color.a() == 0,
            _ => false,
        })
    }
}

#[allow(dead_code)]
#[derive(Debug, Copy, Clone)]
pub struct PositionData {
    pub paragraph: u32,
    pub span: u32,
    pub start_pos: u32,
    pub end_pos: u32,
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    pub direction: u32,
}

#[allow(dead_code)]
#[derive(Debug)]
pub struct ParagraphLayout {
    pub paragraph: skia::textlayout::Paragraph,
    pub x: f32,
    pub y: f32,
    pub spans: Vec<crate::shapes::TextSpan>,
    pub decorations: Vec<TextDecorationSegment>,
}

#[allow(dead_code)]
#[derive(Debug)]
pub struct TextLayoutData {
    pub position_data: Vec<PositionData>,
    pub content_rect: Rect,
    pub paragraphs: Vec<ParagraphLayout>,
}

fn direction_to_int(direction: TextDirection) -> u32 {
    match direction {
        TextDirection::RTL => 0,
        TextDirection::LTR => 1,
    }
}

pub fn calculate_text_layout_data(
    shape: &Shape,
    text_content: &TextContent,
    paragraph_builder_groups: &mut [ParagraphBuilderGroup],
    skip_position_data: bool,
) -> TextLayoutData {
    let selrect_width = shape.selrect().width();
    let text_width = text_content.get_width(selrect_width);
    let selrect_height = shape.selrect().height();
    let x = shape.selrect.x();
    let base_y = shape.selrect.y();
    let mut position_data: Vec<PositionData> = Vec::new();
    let mut previous_line_height = text_content.normalized_line_height();
    let text_paragraphs = text_content.paragraphs();

    // 1. Calculate paragraph heights
    let mut paragraph_heights: Vec<f32> = Vec::new();
    for paragraph_builder_group in paragraph_builder_groups.iter_mut() {
        let group_len = paragraph_builder_group.len();
        let mut paragraph_offset_y = previous_line_height;
        for (builder_index, paragraph_builder) in paragraph_builder_group.iter_mut().enumerate() {
            let mut skia_paragraph = paragraph_builder.build();
            skia_paragraph.layout(text_width);
            if builder_index == group_len - 1 {
                if skia_paragraph.get_line_metrics().is_empty() {
                    paragraph_offset_y = skia_paragraph.ideographic_baseline();
                } else {
                    paragraph_offset_y = skia_paragraph.height();
                }
            }
            if builder_index == 0 {
                paragraph_heights.push(skia_paragraph.height());
            }
        }
        previous_line_height = paragraph_offset_y;
    }

    // 2. Calculate vertical offset and build paragraphs with positions
    let total_text_height: f32 = paragraph_heights.iter().sum();
    let vertical_offset = match shape.vertical_align() {
        VerticalAlign::Center => (selrect_height - total_text_height) / 2.0,
        VerticalAlign::Bottom => selrect_height - total_text_height,
        _ => 0.0,
    };
    let mut paragraph_layouts: Vec<ParagraphLayout> = Vec::new();
    let mut y_accum = base_y + vertical_offset;
    for (i, paragraph_builder_group) in paragraph_builder_groups.iter_mut().enumerate() {
        // For each paragraph in the group (e.g., fill, stroke, etc.)
        for paragraph_builder in paragraph_builder_group.iter_mut() {
            let mut skia_paragraph = paragraph_builder.build();
            skia_paragraph.layout(text_width);

            let spans = if let Some(text_para) = text_paragraphs.get(i) {
                text_para.children().to_vec()
            } else {
                Vec::new()
            };

            // Calculate text decorations for this paragraph
            let mut decorations = Vec::new();
            let line_metrics = skia_paragraph.get_line_metrics();
            for line in &line_metrics {
                let style_metrics: Vec<_> = line
                    .get_style_metrics(line.start_index..line.end_index)
                    .into_iter()
                    .collect();
                let line_baseline = y_accum + line.baseline as f32;
                let (max_underline_thickness, underline_y, max_strike_thickness, strike_y) =
                    calculate_decoration_metrics(&style_metrics, line_baseline);
                for (i, (style_start, style_metric)) in style_metrics.iter().enumerate() {
                    let text_style = &style_metric.text_style;
                    let style_end = style_metrics
                        .get(i + 1)
                        .map(|(next_i, _)| *next_i)
                        .unwrap_or(line.end_index);
                    let seg_start = (*style_start).max(line.start_index);
                    let seg_end = style_end.min(line.end_index);
                    if seg_start >= seg_end {
                        continue;
                    }
                    let rects = skia_paragraph.get_rects_for_range(
                        seg_start..seg_end,
                        skia::textlayout::RectHeightStyle::Tight,
                        skia::textlayout::RectWidthStyle::Tight,
                    );
                    let (segment_width, actual_x_offset) = if !rects.is_empty() {
                        let total_width: f32 = rects.iter().map(|r| r.rect.width()).sum();
                        let skia_x_offset = rects
                            .first()
                            .map(|r| r.rect.left - line.left as f32)
                            .unwrap_or(0.0);
                        (total_width, skia_x_offset)
                    } else {
                        (0.0, 0.0)
                    };
                    let text_left = x + line.left as f32 + actual_x_offset;
                    let text_width = segment_width;
                    use skia::textlayout::TextDecoration;
                    if text_style.decoration().ty == TextDecoration::UNDERLINE {
                        decorations.push(TextDecorationSegment {
                            kind: TextDecoration::UNDERLINE,
                            text_style: (*text_style).clone(),
                            y: underline_y.unwrap_or(line_baseline),
                            thickness: max_underline_thickness,
                            left: text_left,
                            width: text_width,
                        });
                    }
                    if text_style.decoration().ty == TextDecoration::LINE_THROUGH {
                        decorations.push(TextDecorationSegment {
                            kind: TextDecoration::LINE_THROUGH,
                            text_style: (*text_style).clone(),
                            y: strike_y.unwrap_or(line_baseline),
                            thickness: max_strike_thickness,
                            left: text_left,
                            width: text_width,
                        });
                    }
                }
            }
            paragraph_layouts.push(ParagraphLayout {
                paragraph: skia_paragraph,
                x,
                y: y_accum,
                spans: spans.clone(),
                decorations,
            });
        }
        y_accum += paragraph_heights[i];
    }

    // Calculate position data from paragraph_layouts
    if !skip_position_data {
        for (paragraph_index, para_layout) in paragraph_layouts.iter().enumerate() {
            let current_y = para_layout.y;
            let text_paragraph = text_paragraphs.get(paragraph_index);
            if let Some(text_para) = text_paragraph {
                let mut span_ranges: Vec<(usize, usize, usize, String, String)> = vec![];
                let mut cur = 0;
                for (span_index, span) in text_para.children().iter().enumerate() {
                    let transformed_text: String = span.apply_text_transform();
                    let original_text = span.text.clone();
                    let text = transformed_text.clone();
                    let text_len = text.len();
                    span_ranges.push((cur, cur + text_len, span_index, text, original_text));
                    cur += text_len;
                }
                for (start, end, span_index, transformed_text, original_text) in span_ranges {
                    // Skip empty spans to avoid invalid rect calculations
                    if start >= end {
                        continue;
                    }
                    let rects = para_layout.paragraph.get_rects_for_range(
                        start..end,
                        RectHeightStyle::Tight,
                        RectWidthStyle::Tight,
                    );
                    for textbox in rects {
                        let direction = textbox.direct;
                        let mut rect = textbox.rect;
                        let cy = rect.top + rect.height() / 2.0;

                        // Get byte positions from Skia's transformed text layout
                        let glyph_start = para_layout
                            .paragraph
                            .get_glyph_position_at_coordinate((rect.left + 0.1, cy))
                            .position as usize;
                        let glyph_end = para_layout
                            .paragraph
                            .get_glyph_position_at_coordinate((rect.right - 0.1, cy))
                            .position as usize;

                        // Convert to byte positions relative to this span
                        let byte_start = glyph_start.saturating_sub(start);
                        let byte_end = glyph_end.saturating_sub(start);

                        // Convert byte positions to character positions in ORIGINAL text
                        // This handles multi-byte UTF-8 and text transform differences
                        let char_start = transformed_text
                            .char_indices()
                            .position(|(i, _)| i >= byte_start)
                            .unwrap_or(0);
                        let char_end = transformed_text
                            .char_indices()
                            .position(|(i, _)| i >= byte_end)
                            .unwrap_or_else(|| transformed_text.chars().count());

                        // Clamp to original text length for safety
                        let original_char_count = original_text.chars().count();
                        let final_start = char_start.min(original_char_count);
                        let final_end = char_end.min(original_char_count);

                        rect.offset((x, current_y));
                        position_data.push(PositionData {
                            paragraph: paragraph_index as u32,
                            span: span_index as u32,
                            start_pos: final_start as u32,
                            end_pos: final_end as u32,
                            x: rect.x(),
                            y: rect.y(),
                            width: rect.width(),
                            height: rect.height(),
                            direction: direction_to_int(direction),
                        });
                    }
                }
            }
        }
    }

    let content_rect = Rect::from_xywh(x, base_y + vertical_offset, text_width, total_text_height);
    TextLayoutData {
        position_data,
        content_rect,
        paragraphs: paragraph_layouts,
    }
}

pub fn calculate_position_data(
    shape: &Shape,
    text_content: &TextContent,
    skip_position_data: bool,
) -> Vec<PositionData> {
    let mut text_content = text_content.clone();
    text_content.update_layout(shape.selrect);

    let mut paragraph_builders = text_content.paragraph_builder_group_from_text(None);
    let layout_info = calculate_text_layout_data(
        shape,
        &text_content,
        &mut paragraph_builders,
        skip_position_data,
    );

    layout_info.position_data
}
