use crate::{
    math::{Matrix, Rect},
    render::{default_font, DEFAULT_EMOJI_FONT},
};

use core::f32;
use macros::ToJs;
use skia_safe::{
    self as skia,
    paint::{self, Paint},
    textlayout::ParagraphBuilder,
    textlayout::ParagraphStyle,
    textlayout::PositionWithAffinity,
};
use std::collections::HashSet;

use super::FontFamily;
use crate::math::Point;
use crate::shapes::{self, merge_fills};
use crate::utils::{get_fallback_fonts, get_font_collection};
use crate::Uuid;

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
}

const DEFAULT_TEXT_CONTENT_SIZE: f32 = 0.01;

impl TextContentSize {
    pub fn default() -> Self {
        Self {
            width: DEFAULT_TEXT_CONTENT_SIZE,
            height: DEFAULT_TEXT_CONTENT_SIZE,
            max_width: DEFAULT_TEXT_CONTENT_SIZE,
        }
    }

    pub fn new(width: f32, height: f32, max_width: f32) -> Self {
        Self {
            width,
            height,
            max_width,
        }
    }

    pub fn new_with_size(width: f32, height: f32) -> Self {
        Self {
            width,
            height,
            max_width: DEFAULT_TEXT_CONTENT_SIZE,
        }
    }

    pub fn set_size(&mut self, width: f32, height: f32) {
        self.width = width;
        self.height = height;
    }

    pub fn copy_finite_size(&mut self, size: TextContentSize) {
        if f32::is_finite(size.width) {
            self.width = size.width;
        }
        if f32::is_finite(size.max_width) {
            self.max_width = size.max_width;
        } else {
            self.max_width = size.width;
        }
        if f32::is_finite(size.height) {
            self.height = size.height;
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
    pub paragraphs: Vec<Vec<skia_safe::textlayout::Paragraph>>,
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

    pub fn width(&self) -> f32 {
        if self.grow_type() == GrowType::AutoWidth {
            self.size.width
        } else {
            self.bounds.width()
        }
    }

    pub fn grow_type(&self) -> GrowType {
        self.grow_type
    }

    pub fn set_grow_type(&mut self, grow_type: GrowType) {
        self.grow_type = grow_type;
    }

    pub fn visual_bounds(&self) -> (f32, f32) {
        (self.size.width, self.size.height)
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

    pub fn get_caret_position_at(&self, point: &Point) -> Option<PositionWithAffinity> {
        let mut offset_y = 0.0;
        let paragraphs = self.layout.paragraphs.iter().flatten();

        for paragraph in paragraphs {
            let start_y = offset_y;
            let end_y = offset_y + paragraph.height();
            if point.y > start_y && point.y < end_y {
                let position_with_affinity = paragraph.get_glyph_position_at_coordinate(*point);
                return Some(position_with_affinity);
            }
            offset_y += paragraph.height();
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
            for leaf in paragraph.children() {
                let remove_alpha = use_shadow.unwrap_or(false) && !leaf.is_transparent();
                let text_style = leaf.to_style(&self.bounds(), fallback_fonts, remove_alpha);
                let text = leaf.apply_text_transform();
                builder.push_style(&text_style);
                builder.add_text(&text);
            }
            paragraph_group.push(vec![builder]);
        }

        paragraph_group
    }

    /// Performs a text auto layout without width limits.
    /// This should be the same as text_auto_layout.
    fn build_paragraphs_from_paragraph_builders(
        &self,
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

    /// Performs an Auto Width text layout.
    fn text_layout_auto_width(&self) -> TextContentLayoutResult {
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);
        let paragraphs =
            self.build_paragraphs_from_paragraph_builders(&mut paragraph_builders, f32::MAX);
        let (width, height) =
            paragraphs
                .iter()
                .flatten()
                .fold((0.0, 0.0), |(auto_width, auto_height), paragraph| {
                    (
                        f32::max(paragraph.max_intrinsic_width(), auto_width),
                        auto_height + paragraph.height(),
                    )
                });

        let size = TextContentSize::new(width.ceil(), height.ceil(), width.ceil());
        TextContentLayoutResult(paragraph_builders, paragraphs, size)
    }

    /// Private function that performs
    /// Performs an Auto Height text layout.
    fn text_layout_auto_height(&self) -> TextContentLayoutResult {
        let width = self.width();
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);
        let paragraphs =
            self.build_paragraphs_from_paragraph_builders(&mut paragraph_builders, f32::INFINITY);
        let height = paragraphs
            .iter()
            .flatten()
            .fold(0.0, |auto_height, paragraph| {
                auto_height + paragraph.height()
            });

        let size = TextContentSize::new_with_size(width.ceil(), height.ceil());
        TextContentLayoutResult(paragraph_builders, paragraphs, size)
    }

    /// Performs a Fixed text layout.
    fn text_layout_fixed(&self) -> TextContentLayoutResult {
        let width = self.width();
        let mut paragraph_builders = self.paragraph_builder_group_from_text(None);
        let paragraphs =
            self.build_paragraphs_from_paragraph_builders(&mut paragraph_builders, width);

        let size = TextContentSize::new_with_size(width.ceil(), f32::INFINITY);
        TextContentLayoutResult(paragraph_builders, paragraphs, size)
    }

    pub fn needs_update_layout(&self) -> bool {
        self.layout.needs_update()
    }

    pub fn set_layout_from_result(&mut self, result: TextContentLayoutResult) {
        self.layout.set(result.0, result.1);
        self.size.copy_finite_size(result.2);
    }

    pub fn update_layout(&mut self, selrect: Rect) -> TextContentSize {
        self.size.set_size(selrect.width(), selrect.height());
        match self.grow_type() {
            GrowType::AutoHeight => {
                let result = self.text_layout_auto_height();
                self.set_layout_from_result(result);
            }
            GrowType::AutoWidth => {
                let result = self.text_layout_auto_width();
                self.set_layout_from_result(result);
            }
            GrowType::Fixed => {
                let result = self.text_layout_fixed();
                self.set_layout_from_result(result);
            }
        }
        self.size
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
    typography_ref_file: Uuid,
    typography_ref_id: Uuid,
    children: Vec<TextLeaf>,
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
            typography_ref_file: Uuid::nil(),
            typography_ref_id: Uuid::nil(),
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
        typography_ref_file: Uuid,
        typography_ref_id: Uuid,
        children: Vec<TextLeaf>,
    ) -> Self {
        Self {
            text_align,
            text_direction,
            text_decoration,
            text_transform,
            line_height,
            letter_spacing,
            typography_ref_file,
            typography_ref_id,
            children,
        }
    }

    #[allow(dead_code)]
    fn set_children(&mut self, children: Vec<TextLeaf>) {
        self.children = children;
    }

    pub fn children(&self) -> &[TextLeaf] {
        &self.children
    }

    #[allow(dead_code)]
    fn add_leaf(&mut self, leaf: TextLeaf) {
        self.children.push(leaf);
    }

    // FIXME: move serialization to wasm module
    pub fn paragraph_to_style(&self) -> ParagraphStyle {
        let mut style = ParagraphStyle::default();
        style.set_text_align(self.text_align);
        style.set_text_direction(self.text_direction);

        if !self.children.is_empty() {
            let reference_child = self
                .children
                .iter()
                .find(|child| !child.text.trim().is_empty())
                .unwrap_or(&self.children[0]);

            let mut strut_style = skia::textlayout::StrutStyle::default();
            let line_height = self.line_height.max(0.0);
            strut_style.set_font_size(reference_child.font_size);
            strut_style.set_height(line_height);
            strut_style.set_height_override(true);
            strut_style.set_half_leading(true);
            strut_style.set_strut_enabled(true);
            strut_style.set_force_strut_height(true);

            let font_families = vec![
                reference_child.serialized_font_family(),
                default_font(),
                DEFAULT_EMOJI_FONT.to_string(),
            ];
            strut_style.set_font_families(&font_families);

            style.set_strut_style(strut_style);
        }

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
pub struct TextLeaf {
    text: String,
    font_family: FontFamily,
    font_size: f32,
    letter_spacing: f32,
    font_weight: i32,
    font_variant_id: Uuid,
    text_decoration: Option<TextDecoration>,
    text_transform: Option<TextTransform>,
    text_direction: TextDirection,
    fills: Vec<shapes::Fill>,
}

impl TextLeaf {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        text: String,
        font_family: FontFamily,
        font_size: f32,
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
    ) -> skia::textlayout::TextStyle {
        let mut style = skia::textlayout::TextStyle::default();

        let mut paint = paint::Paint::default();

        if remove_alpha {
            paint.set_color(skia::Color::BLACK);
            paint.set_alpha(255);
        } else {
            paint = merge_fills(&self.fills, *content_bounds);
        }

        style.set_foreground_paint(&paint);
        style.set_font_size(self.font_size);
        style.set_letter_spacing(self.letter_spacing);
        style.set_half_leading(false);

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

        style
    }

    pub fn to_stroke_style(
        &self,
        stroke_paint: &Paint,
        fallback_fonts: &HashSet<String>,
        remove_alpha: bool,
    ) -> skia::textlayout::TextStyle {
        let mut style = self.to_style(&Rect::default(), fallback_fonts, remove_alpha);
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

    pub fn apply_text_transform(&self) -> String {
        match self.text_transform {
            Some(TextTransform::Uppercase) => self.text.to_uppercase(),
            Some(TextTransform::Lowercase) => self.text.to_lowercase(),
            Some(TextTransform::Capitalize) => self
                .text
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
            None => self.text.clone(),
        }
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
