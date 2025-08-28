use crate::{
    math::{Matrix, Rect},
    render::{default_font, filters::compose_filters, DEFAULT_EMOJI_FONT},
};
use skia_safe::{
    self as skia,
    paint::Paint,
    textlayout::{ParagraphBuilder, ParagraphStyle},
    ImageFilter, MaskFilter,
};
use std::collections::HashSet;

use super::FontFamily;
use crate::shapes::{self, merge_fills, set_paint_fill, Stroke, StrokeKind};
use crate::utils::{get_fallback_fonts, get_font_collection, uuid_from_u32};
use crate::wasm::fills::parse_fills_from_bytes;
use crate::Uuid;

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum GrowType {
    Fixed,
    AutoWidth,
    AutoHeight,
}

impl GrowType {
    pub fn from(grow_type: u8) -> Self {
        match grow_type {
            0 => Self::Fixed,
            1 => Self::AutoWidth,
            2 => Self::AutoHeight,
            _ => unreachable!(),
        }
    }
}

#[derive(Debug, PartialEq, Clone)]
pub struct TextContent {
    pub paragraphs: Vec<Paragraph>,
    pub bounds: Rect,
    pub grow_type: GrowType,
}

pub fn build_paragraphs_with_width(
    paragraphs: &mut [Vec<ParagraphBuilder>],
    width: f32,
) -> Vec<Vec<skia_safe::textlayout::Paragraph>> {
    paragraphs
        .iter_mut()
        .map(|builders| {
            builders
                .iter_mut()
                .map(|builder| {
                    let mut paragraph = builder.build();
                    // For auto-width, always layout with infinite width first to get intrinsic width
                    paragraph.layout(f32::MAX);
                    let intrinsic_width = paragraph.max_intrinsic_width().ceil();
                    // Use the larger of the requested width or intrinsic width to prevent line breaks
                    let final_width = f32::max(width, intrinsic_width);
                    paragraph.layout(final_width);
                    paragraph
                })
                .collect()
        })
        .collect()
}

impl TextContent {
    pub fn new(bounds: Rect, grow_type: GrowType) -> Self {
        Self {
            paragraphs: Vec::new(),
            bounds,
            grow_type,
        }
    }

    pub fn new_bounds(&self, bounds: Rect) -> Self {
        let paragraphs = self.paragraphs.clone();
        let grow_type = self.grow_type;
        Self {
            paragraphs,
            bounds,
            grow_type,
        }
    }

    pub fn set_xywh(&mut self, x: f32, y: f32, w: f32, h: f32) {
        self.bounds = Rect::from_xywh(x, y, w, h);
    }

    #[allow(dead_code)]
    pub fn width(&self) -> f32 {
        self.bounds.width()
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

    pub fn to_paragraphs(
        &self,
        blur: Option<&ImageFilter>,
        blur_mask: Option<&MaskFilter>,
    ) -> Vec<Vec<ParagraphBuilder>> {
        let fonts = get_font_collection();
        let fallback_fonts = get_fallback_fonts();
        let mut paragraph_group = Vec::new();

        for paragraph in &self.paragraphs {
            let paragraph_style = paragraph.paragraph_to_style();
            let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
            for leaf in &paragraph.children {
                let text_style = leaf.to_style(&self.bounds, fallback_fonts, blur, blur_mask);
                let text = leaf.apply_text_transform();
                builder.push_style(&text_style);
                builder.add_text(&text);
            }
            paragraph_group.push(vec![builder]);
        }

        paragraph_group
    }

    pub fn to_stroke_paragraphs(
        &self,
        stroke: &Stroke,
        bounds: &Rect,
        blur: Option<&ImageFilter>,
        blur_mask: Option<&MaskFilter>,
        count_inner_strokes: usize,
    ) -> Vec<Vec<ParagraphBuilder>> {
        let fallback_fonts = get_fallback_fonts();
        let fonts = get_font_collection();
        let mut paragraph_group = Vec::new();

        for paragraph in &self.paragraphs {
            let mut stroke_paragraphs_map: std::collections::HashMap<usize, ParagraphBuilder> =
                std::collections::HashMap::new();

            for leaf in paragraph.children.iter() {
                let mut text_paint = merge_fills(&leaf.fills, *bounds);
                if let Some(blur_mask) = blur_mask {
                    text_paint.set_mask_filter(blur_mask.clone());
                }
                let stroke_paints = get_text_stroke_paints(
                    stroke,
                    bounds,
                    &text_paint,
                    blur,
                    blur_mask,
                    count_inner_strokes,
                );
                let text: String = leaf.apply_text_transform();

                for (paint_idx, stroke_paint) in stroke_paints.iter().enumerate() {
                    let builder = stroke_paragraphs_map.entry(paint_idx).or_insert_with(|| {
                        let paragraph_style = paragraph.paragraph_to_style();
                        ParagraphBuilder::new(&paragraph_style, fonts)
                    });
                    let stroke_paint = stroke_paint.clone();
                    let stroke_style =
                        leaf.to_stroke_style(&stroke_paint, fallback_fonts, blur, blur_mask);
                    builder.push_style(&stroke_style);
                    builder.add_text(&text);
                }
            }

            let stroke_paragraphs: Vec<ParagraphBuilder> = (0..stroke_paragraphs_map.len())
                .map(|i| stroke_paragraphs_map.remove(&i).unwrap())
                .collect();

            paragraph_group.push(stroke_paragraphs);
        }

        paragraph_group
    }

    pub fn get_width(&self) -> f32 {
        if self.grow_type() == GrowType::AutoWidth {
            let temp_paragraphs = self.to_paragraphs(None, None);
            let mut temp_paragraphs = temp_paragraphs;
            auto_width(&mut temp_paragraphs, f32::MAX).ceil()
        } else {
            self.width()
        }
    }

    pub fn grow_type(&self) -> GrowType {
        self.grow_type
    }

    pub fn set_grow_type(&mut self, grow_type: GrowType) {
        self.grow_type = grow_type;
    }

    pub fn visual_bounds(&self) -> (f32, f32) {
        let paragraph_width = self.get_width();
        let mut paragraphs = self.to_paragraphs(None, None);
        let paragraph_height = auto_height(&mut paragraphs, paragraph_width);
        (paragraph_width, paragraph_height)
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
}

impl Default for TextContent {
    fn default() -> Self {
        Self {
            paragraphs: vec![],
            bounds: Rect::default(),
            grow_type: GrowType::Fixed,
        }
    }
}

#[derive(Debug, PartialEq, Clone)]
pub struct Paragraph {
    num_leaves: u32,
    text_align: u8,
    text_direction: u8,
    text_decoration: u8,
    text_transform: u8,
    line_height: f32,
    letter_spacing: f32,
    typography_ref_file: Uuid,
    typography_ref_id: Uuid,
    children: Vec<TextLeaf>,
}

impl Default for Paragraph {
    fn default() -> Self {
        Self {
            num_leaves: 0,
            text_align: 0,
            text_direction: 0,
            text_decoration: 0,
            text_transform: 0,
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
        num_leaves: u32,
        text_align: u8,
        text_direction: u8,
        text_decoration: u8,
        text_transform: u8,
        line_height: f32,
        letter_spacing: f32,
        typography_ref_file: Uuid,
        typography_ref_id: Uuid,
        children: Vec<TextLeaf>,
    ) -> Self {
        Self {
            num_leaves,
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

    #[allow(dead_code)]
    pub fn get_children(&self) -> &Vec<TextLeaf> {
        &self.children
    }

    #[allow(dead_code)]
    fn add_leaf(&mut self, leaf: TextLeaf) {
        self.children.push(leaf);
    }

    pub fn paragraph_to_style(&self) -> ParagraphStyle {
        let mut style = ParagraphStyle::default();
        style.set_text_align(match self.text_align {
            0 => skia::textlayout::TextAlign::Left,
            1 => skia::textlayout::TextAlign::Center,
            2 => skia::textlayout::TextAlign::Right,
            3 => skia::textlayout::TextAlign::Justify,
            _ => skia::textlayout::TextAlign::Left,
        });
        style.set_text_direction(match self.text_direction {
            0 => skia::textlayout::TextDirection::LTR,
            1 => skia::textlayout::TextDirection::RTL,
            _ => skia::textlayout::TextDirection::LTR,
        });

        if !self.children.is_empty() {
            let reference_child = self
                .children
                .iter()
                .find(|child| !child.text.trim().is_empty())
                .unwrap_or(&self.children[0]);

            let mut strut_style = skia::textlayout::StrutStyle::default();
            let line_height = self.line_height.max(1.0);
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
    font_style: u8,
    font_weight: i32,
    font_variant_id: Uuid,
    text_decoration: u8,
    text_transform: u8,
    text_direction: u8,
    fills: Vec<shapes::Fill>,
}

impl TextLeaf {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        text: String,
        font_family: FontFamily,
        font_size: f32,
        letter_spacing: f32,
        font_style: u8,
        text_decoration: u8,
        text_transform: u8,
        text_direction: u8,
        font_weight: i32,
        font_variant_id: Uuid,
        fills: Vec<shapes::Fill>,
    ) -> Self {
        Self {
            text,
            font_family,
            font_size,
            letter_spacing,
            font_style,
            text_decoration,
            text_transform,
            text_direction,
            font_weight,
            font_variant_id,
            fills,
        }
    }

    pub fn to_style(
        &self,
        content_bounds: &Rect,
        fallback_fonts: &HashSet<String>,
        _blur: Option<&ImageFilter>,
        blur_mask: Option<&MaskFilter>,
    ) -> skia::textlayout::TextStyle {
        let mut style = skia::textlayout::TextStyle::default();
        let mut paint = merge_fills(&self.fills, *content_bounds);

        if let Some(blur_mask) = blur_mask {
            paint.set_mask_filter(blur_mask.clone());
        }

        style.set_foreground_paint(&paint);
        style.set_font_size(self.font_size);
        style.set_letter_spacing(self.letter_spacing);
        style.set_half_leading(false);

        style.set_decoration_type(match self.text_decoration {
            0 => skia::textlayout::TextDecoration::NO_DECORATION,
            1 => skia::textlayout::TextDecoration::UNDERLINE,
            2 => skia::textlayout::TextDecoration::LINE_THROUGH,
            3 => skia::textlayout::TextDecoration::OVERLINE,
            _ => skia::textlayout::TextDecoration::NO_DECORATION,
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
        blur: Option<&ImageFilter>,
        blur_mask: Option<&MaskFilter>,
    ) -> skia::textlayout::TextStyle {
        let mut style = self.to_style(&Rect::default(), fallback_fonts, blur, blur_mask);
        style.set_foreground_paint(stroke_paint);
        style.set_font_size(self.font_size);
        style.set_letter_spacing(self.letter_spacing);
        style.set_decoration_type(match self.text_decoration {
            0 => skia::textlayout::TextDecoration::NO_DECORATION,
            1 => skia::textlayout::TextDecoration::UNDERLINE,
            2 => skia::textlayout::TextDecoration::LINE_THROUGH,
            3 => skia::textlayout::TextDecoration::OVERLINE,
            _ => skia::textlayout::TextDecoration::NO_DECORATION,
        });
        style
    }

    fn serialized_font_family(&self) -> String {
        format!("{}", self.font_family)
    }

    pub fn apply_text_transform(&self) -> String {
        match self.text_transform {
            1 => self.text.to_uppercase(),
            2 => self.text.to_lowercase(),
            3 => self
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
            _ => self.text.clone(),
        }
    }

    pub fn scale_content(&mut self, value: f32) {
        self.font_size *= value;
    }
}

const RAW_PARAGRAPH_DATA_SIZE: usize = std::mem::size_of::<RawParagraphData>();
const RAW_LEAF_DATA_SIZE: usize = std::mem::size_of::<RawTextLeaf>();
pub const RAW_LEAF_FILLS_SIZE: usize = 160;

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct RawTextLeaf {
    font_style: u8,
    text_decoration: u8,
    text_transform: u8,
    font_size: f32,
    letter_spacing: f32,
    font_weight: i32,
    font_id: [u32; 4],
    font_family: [u8; 4],
    font_variant_id: [u32; 4],
    text_length: u32,
    total_fills: u32,
}

impl From<[u8; RAW_LEAF_DATA_SIZE]> for RawTextLeaf {
    fn from(bytes: [u8; RAW_LEAF_DATA_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl TryFrom<&[u8]> for RawTextLeaf {
    type Error = String;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_LEAF_DATA_SIZE] = bytes
            .get(0..RAW_LEAF_DATA_SIZE)
            .and_then(|slice| slice.try_into().ok())
            .ok_or("Invalid text leaf data".to_string())?;
        Ok(RawTextLeaf::from(data))
    }
}

#[allow(dead_code)]
#[repr(C)]
#[derive(Debug, Clone)]
pub struct RawTextLeafData {
    font_style: u8,
    text_decoration: u8,
    text_transform: u8,
    text_direction: u8,
    font_size: f32,
    letter_spacing: f32,
    font_weight: i32,
    font_id: [u32; 4],
    font_family: [u8; 4],
    font_variant_id: [u32; 4],
    text_length: u32,
    total_fills: u32,
    fills: Vec<shapes::Fill>,
}

impl From<&[u8]> for RawTextLeafData {
    fn from(bytes: &[u8]) -> Self {
        let text_leaf: RawTextLeaf = RawTextLeaf::try_from(bytes).unwrap();
        let total_fills = text_leaf.total_fills as usize;

        // Use checked_mul to prevent overflow
        let fills_size = total_fills
            .checked_mul(RAW_LEAF_FILLS_SIZE)
            .expect("Overflow occurred while calculating fills size");

        let fills_start = RAW_LEAF_DATA_SIZE;
        let fills_end = fills_start + fills_size;
        let buffer = &bytes[fills_start..fills_end];
        let fills = parse_fills_from_bytes(buffer, total_fills);

        Self {
            font_style: text_leaf.font_style,
            text_decoration: text_leaf.text_decoration,
            text_transform: text_leaf.text_transform,
            text_direction: 0, // TODO: Añadirlo
            font_size: text_leaf.font_size,
            letter_spacing: text_leaf.letter_spacing,
            font_weight: text_leaf.font_weight,
            font_id: text_leaf.font_id,
            font_family: text_leaf.font_family,
            font_variant_id: text_leaf.font_variant_id,
            text_length: text_leaf.text_length,
            total_fills: text_leaf.total_fills,
            fills,
        }
    }
}

#[repr(C)]
#[repr(align(4))]
#[derive(Debug, Clone, Copy)]
pub struct RawParagraphData {
    num_leaves: u32,
    text_align: u8,
    text_direction: u8,
    text_decoration: u8,
    text_transform: u8,
    line_height: f32,
    letter_spacing: f32,
    typography_ref_file: [u32; 4],
    typography_ref_id: [u32; 4],
}

impl From<[u8; RAW_PARAGRAPH_DATA_SIZE]> for RawParagraphData {
    fn from(bytes: [u8; RAW_PARAGRAPH_DATA_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl TryFrom<&[u8]> for RawParagraphData {
    type Error = String;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_PARAGRAPH_DATA_SIZE] = bytes
            .get(0..RAW_PARAGRAPH_DATA_SIZE)
            .and_then(|slice| slice.try_into().ok())
            .ok_or("Invalid paragraph data".to_string())?;
        Ok(RawParagraphData::from(data))
    }
}

impl RawTextData {
    fn text_from_bytes(buffer: &[u8], offset: usize, text_length: u32) -> (String, usize) {
        let text_length = text_length as usize;
        let text_end = offset + text_length;

        if text_end > buffer.len() {
            panic!(
                "Invalid text range: offset={}, text_end={}, buffer_len={}",
                offset,
                text_end,
                buffer.len()
            );
        }

        let text_utf8 = buffer[offset..text_end].to_vec();
        if text_utf8.is_empty() {
            return (String::new(), text_end);
        }

        let text = String::from_utf8_lossy(&text_utf8).to_string();
        (text, text_end)
    }
}

pub struct RawTextData {
    pub paragraph: Paragraph,
}

impl From<&Vec<u8>> for RawTextData {
    fn from(bytes: &Vec<u8>) -> Self {
        let paragraph = RawParagraphData::try_from(&bytes[..RAW_PARAGRAPH_DATA_SIZE]).unwrap();
        let mut offset = RAW_PARAGRAPH_DATA_SIZE;
        let mut raw_text_leaves: Vec<RawTextLeafData> = Vec::new();
        let mut text_leaves: Vec<TextLeaf> = Vec::new();

        for _ in 0..paragraph.num_leaves {
            let text_leaf = RawTextLeafData::from(&bytes[offset..]);
            raw_text_leaves.push(text_leaf.clone());
            offset += RAW_LEAF_DATA_SIZE + (text_leaf.total_fills as usize * RAW_LEAF_FILLS_SIZE);
        }

        for text_leaf in raw_text_leaves.iter() {
            let (text, new_offset) =
                RawTextData::text_from_bytes(bytes, offset, text_leaf.text_length);
            offset = new_offset;

            let font_id = uuid_from_u32(text_leaf.font_id);
            let font_variant_id = uuid_from_u32(text_leaf.font_variant_id);

            let font_family = FontFamily::new(
                font_id,
                text_leaf.font_weight as u32,
                text_leaf.font_style.into(),
            );

            let new_text_leaf = TextLeaf::new(
                text,
                font_family,
                text_leaf.font_size,
                text_leaf.letter_spacing,
                text_leaf.font_style,
                text_leaf.text_decoration,
                text_leaf.text_transform,
                text_leaf.text_direction,
                text_leaf.font_weight,
                font_variant_id,
                text_leaf.fills.clone(),
            );
            text_leaves.push(new_text_leaf);
        }

        let typography_ref_file = uuid_from_u32(paragraph.typography_ref_file);
        let typography_ref_id = uuid_from_u32(paragraph.typography_ref_id);

        let paragraph = Paragraph::new(
            paragraph.num_leaves,
            paragraph.text_align,
            paragraph.text_direction,
            paragraph.text_decoration,
            paragraph.text_transform,
            paragraph.line_height,
            paragraph.letter_spacing,
            typography_ref_file,
            typography_ref_id,
            text_leaves.clone(),
        );

        Self { paragraph }
    }
}

pub fn get_built_paragraphs(
    paragraphs: &mut [Vec<ParagraphBuilder>],
    width: f32,
) -> Vec<Vec<skia_safe::textlayout::Paragraph>> {
    build_paragraphs_with_width(paragraphs, width)
}

pub fn auto_width(paragraphs: &mut [Vec<ParagraphBuilder>], width: f32) -> f32 {
    let built_paragraphs = get_built_paragraphs(paragraphs, width);

    built_paragraphs
        .iter()
        .flatten()
        .fold(0.0, |auto_width, p| {
            f32::max(p.max_intrinsic_width(), auto_width)
        })
}

pub fn auto_height(paragraphs: &mut [Vec<ParagraphBuilder>], width: f32) -> f32 {
    paragraphs.iter_mut().fold(0.0, |auto_height, p| {
        p.iter_mut().fold(auto_height, |auto_height, paragraph| {
            let mut paragraph = paragraph.build();
            paragraph.layout(width);
            auto_height + paragraph.height()
        })
    })
}

fn get_text_stroke_paints(
    stroke: &Stroke,
    bounds: &Rect,
    text_paint: &Paint,
    blur: Option<&ImageFilter>,
    blur_mask: Option<&MaskFilter>,
    count_inner_strokes: usize,
) -> Vec<Paint> {
    let mut paints = Vec::new();

    match stroke.kind {
        StrokeKind::Inner => {
            let shader = text_paint.shader();
            let mut is_opaque = true;

            if shader.is_some() {
                is_opaque = shader.unwrap().is_opaque();
            }

            if is_opaque && count_inner_strokes == 1 {
                let mut paint = text_paint.clone();
                paint.set_style(skia::PaintStyle::Fill);
                paint.set_anti_alias(true);
                if let Some(blur) = blur {
                    paint.set_image_filter(blur.clone());
                }
                paints.push(paint);
                let mut paint = skia::Paint::default();
                paint.set_style(skia::PaintStyle::Stroke);
                paint.set_blend_mode(skia::BlendMode::SrcIn);
                paint.set_anti_alias(true);
                paint.set_stroke_width(stroke.width * 2.0);
                set_paint_fill(&mut paint, &stroke.fill, bounds);
                if let Some(blur) = blur {
                    paint.set_image_filter(blur.clone());
                }
                paints.push(paint);
            } else {
                let mut paint = text_paint.clone();
                paint.set_style(skia::PaintStyle::Fill);
                paint.set_anti_alias(false);
                set_paint_fill(&mut paint, &stroke.fill, bounds);
                paints.push(paint);

                let mut paint = skia::Paint::default();
                let image_filter =
                    skia_safe::image_filters::erode((stroke.width, stroke.width), None, None);

                let filter = compose_filters(blur, image_filter.as_ref());
                paint.set_image_filter(filter);
                paint.set_anti_alias(false);
                paint.set_blend_mode(skia::BlendMode::DstOut);
                paints.push(paint);
            }
        }
        StrokeKind::Center => {
            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Stroke);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width);

            set_paint_fill(&mut paint, &stroke.fill, bounds);
            if let Some(blur) = blur {
                paint.set_image_filter(blur.clone());
            }

            paints.push(paint);
        }
        StrokeKind::Outer => {
            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Stroke);
            paint.set_blend_mode(skia::BlendMode::DstOver);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width * 2.0);
            set_paint_fill(&mut paint, &stroke.fill, bounds);
            if let Some(blur_mask) = blur_mask {
                paint.set_mask_filter(blur_mask.clone());
            }
            paints.push(paint);

            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Fill);
            paint.set_blend_mode(skia::BlendMode::Clear);
            paint.set_color(skia::Color::TRANSPARENT);
            paint.set_anti_alias(true);
            if let Some(blur_mask) = blur_mask {
                paint.set_mask_filter(blur_mask.clone());
            }
            paints.push(paint);
        }
    }

    paints
}
