use crate::{
    math::Rect,
    render::{default_font, DEFAULT_EMOJI_FONT},
};
use skia_safe::{
    self as skia,
    paint::Paint,
    textlayout::{FontCollection, ParagraphBuilder, ParagraphStyle},
};

use super::FontFamily;
use crate::shapes::{self, merge_fills, set_paint_fill, Fill, Stroke, StrokeKind};
use crate::utils::uuid_from_u32;
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
    paragraphs: Vec<Paragraph>,
    bounds: Rect,
    grow_type: GrowType,
}

pub fn set_paragraphs_width(width: f32, paragraphs: &mut Vec<Vec<skia::textlayout::Paragraph>>) {
    for group in paragraphs {
        for paragraph in group {
            // We first set max so we can get the min_intrinsic_width (this is the min word size)
            // then after we set either the real with or the min.
            // This is done this way so the words are not break into lines.
            paragraph.layout(f32::MAX);
            paragraph.layout(f32::max(width, paragraph.min_intrinsic_width().ceil()));
        }
    }
}

impl TextContent {
    pub fn new(bounds: Rect, grow_type: GrowType) -> Self {
        let mut res = Self::default();
        res.bounds = bounds;
        res.grow_type = grow_type;
        res
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

    pub fn to_paragraphs(&self, fonts: &FontCollection) -> Vec<Vec<skia::textlayout::Paragraph>> {
        let mut paragraph_group = Vec::new();
        let paragraphs = self
            .paragraphs
            .iter()
            .map(|p| {
                let paragraph_style = p.paragraph_to_style();
                let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
                for leaf in &p.children {
                    let text_style = leaf.to_style(p, &self.bounds); // FIXME
                    let text = leaf.apply_text_transform(p.text_transform);
                    builder.push_style(&text_style);
                    builder.add_text(&text);
                    builder.pop();
                }
                builder.build()
            })
            .collect();
        paragraph_group.push(paragraphs);
        paragraph_group
    }

    pub fn to_stroke_paragraphs(
        &self,
        stroke: &Stroke,
        bounds: &Rect,
        fonts: &FontCollection,
    ) -> Vec<Vec<skia::textlayout::Paragraph>> {
        let mut paragraph_group = Vec::new();
        let stroke_paints = get_text_stroke_paints(&stroke, &bounds);

        for stroke_paint in stroke_paints {
            let mut stroke_paragraphs = Vec::new();
            for paragraph in &self.paragraphs {
                let paragraph_style = paragraph.paragraph_to_style();
                let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
                for leaf in &paragraph.children {
                    let stroke_style = leaf.to_stroke_style(paragraph, &stroke_paint);
                    let text: String = leaf.apply_text_transform(paragraph.text_transform);
                    builder.push_style(&stroke_style);
                    builder.add_text(&text);
                    builder.pop();
                }
                let p = builder.build();
                stroke_paragraphs.push(p);
            }
            paragraph_group.push(stroke_paragraphs);
        }
        paragraph_group
    }

    pub fn collect_paragraphs(
        &self,
        mut paragraphs: Vec<Vec<skia::textlayout::Paragraph>>,
    ) -> Vec<Vec<skia::textlayout::Paragraph>> {
        if self.grow_type() == GrowType::AutoWidth {
            set_paragraphs_width(f32::MAX, &mut paragraphs);
            let max_width = auto_width(&paragraphs).ceil();
            set_paragraphs_width(max_width, &mut paragraphs);
        } else {
            set_paragraphs_width(self.width(), &mut paragraphs);
        }
        paragraphs
    }

    pub fn get_skia_paragraphs(
        &self,
        fonts: &FontCollection,
    ) -> Vec<Vec<skia::textlayout::Paragraph>> {
        self.collect_paragraphs(self.to_paragraphs(fonts))
    }

    pub fn get_skia_stroke_paragraphs(
        &self,
        stroke: &Stroke,
        bounds: &Rect,
        fonts: &FontCollection,
    ) -> Vec<Vec<skia::textlayout::Paragraph>> {
        self.collect_paragraphs(self.to_stroke_paragraphs(stroke, bounds, fonts))
    }

    pub fn grow_type(&self) -> GrowType {
        self.grow_type
    }

    pub fn set_grow_type(&mut self, grow_type: GrowType) {
        self.grow_type = grow_type;
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
    text_align: u8,
    text_decoration: u8,
    text_direction: u8,
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
            text_align: 0,
            text_decoration: 0,
            text_direction: 0,
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
    pub fn new(
        text_align: u8,
        text_decoration: u8,
        text_direction: u8,
        text_transform: u8,
        line_height: f32,
        letter_spacing: f32,
        typography_ref_file: Uuid,
        typography_ref_id: Uuid,
        children: Vec<TextLeaf>,
    ) -> Self {
        Self {
            text_align,
            text_decoration,
            text_direction,
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
        style.set_height(self.line_height);
        style.set_text_direction(match self.text_direction {
            0 => skia::textlayout::TextDirection::LTR,
            1 => skia::textlayout::TextDirection::RTL,
            _ => skia::textlayout::TextDirection::LTR,
        });
        style
    }
}

#[derive(Debug, PartialEq, Clone)]
pub struct TextLeaf {
    text: String,
    font_family: FontFamily,
    font_size: f32,
    font_style: u8,
    font_weight: i32,
    font_variant_id: Uuid,
    fills: Vec<shapes::Fill>,
}

impl TextLeaf {
    pub fn new(
        text: String,
        font_family: FontFamily,
        font_size: f32,
        font_style: u8,
        font_weight: i32,
        font_variant_id: Uuid,
        fills: Vec<shapes::Fill>,
    ) -> Self {
        Self {
            text,
            font_family,
            font_size,
            font_style,
            font_weight,
            font_variant_id,
            fills,
        }
    }

    pub fn to_style(
        &self,
        paragraph: &Paragraph,
        content_bounds: &Rect,
    ) -> skia::textlayout::TextStyle {
        let mut style = skia::textlayout::TextStyle::default();

        let bounding_box = Rect::from_xywh(
            content_bounds.x(),
            content_bounds.y(),
            self.font_size * self.text.len() as f32,
            self.font_size,
        );

        let paint = merge_fills(&self.fills, bounding_box);
        style.set_foreground_paint(&paint);
        style.set_font_size(self.font_size);
        style.set_letter_spacing(paragraph.letter_spacing);
        style.set_height(paragraph.line_height);
        style.set_height_override(true);
        style.set_decoration_type(match paragraph.text_decoration {
            0 => skia::textlayout::TextDecoration::NO_DECORATION,
            1 => skia::textlayout::TextDecoration::UNDERLINE,
            2 => skia::textlayout::TextDecoration::LINE_THROUGH,
            3 => skia::textlayout::TextDecoration::OVERLINE,
            _ => skia::textlayout::TextDecoration::NO_DECORATION,
        });

        style.set_font_families(&[
            self.serialized_font_family(),
            default_font(),
            DEFAULT_EMOJI_FONT.to_string(),
        ]);

        style
    }

    pub fn to_stroke_style(
        &self,
        paragraph: &Paragraph,
        stroke_paint: &Paint,
    ) -> skia::textlayout::TextStyle {
        let mut style = self.to_style(paragraph, &Rect::default());
        style.set_foreground_paint(stroke_paint);
        style
    }

    fn serialized_font_family(&self) -> String {
        format!("{}", self.font_family)
    }

    pub fn apply_text_transform(&self, transform: u8) -> String {
        match transform {
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
}

// FIXME
pub const RAW_PARAGRAPH_DATA_SIZE: usize = 48;
pub const RAW_LEAF_DATA_SIZE: usize = 56;
pub const RAW_LEAF_FILLS_SIZE: usize = 160;

#[repr(C)]
#[derive(Debug)]
pub struct RawTextLeafData {
    font_style: u8,
    font_size: f32,
    font_weight: i32,
    font_id: [u32; 4],
    font_family: [u8; 4],
    font_variant_id: [u32; 4],
    text_length: u32,
    total_fills: u32,
    fills: Vec<shapes::Fill>,
}

#[repr(C)]
#[derive(Debug)]
pub struct RawParagraphData {
    text_align: u8,
    text_transform: u8,
    text_decoration: u8,
    text_direction: u8,
    line_height: f32,
    letter_spacing: f32,
    typography_ref_file: [u32; 4],
    typography_ref_id: [u32; 4],
}

impl From<[u8; RAW_PARAGRAPH_DATA_SIZE]> for RawParagraphData {
    fn from(bytes: [u8; RAW_PARAGRAPH_DATA_SIZE]) -> Self {
        Self {
            text_align: bytes[4],
            text_direction: bytes[5],
            text_decoration: bytes[6],
            text_transform: bytes[7],
            line_height: f32::from_be_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
            letter_spacing: f32::from_be_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]),
            typography_ref_file: [
                u32::from_be_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]),
                u32::from_be_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]),
                u32::from_be_bytes([bytes[24], bytes[25], bytes[26], bytes[27]]),
                u32::from_be_bytes([bytes[28], bytes[29], bytes[30], bytes[31]]),
            ],
            typography_ref_id: [
                u32::from_be_bytes([bytes[32], bytes[33], bytes[34], bytes[35]]),
                u32::from_be_bytes([bytes[36], bytes[37], bytes[38], bytes[39]]),
                u32::from_be_bytes([bytes[40], bytes[41], bytes[42], bytes[43]]),
                u32::from_be_bytes([bytes[44], bytes[45], bytes[46], bytes[47]]),
            ],
        }
    }
}

impl RawTextLeafData {
    pub fn from(bytes: &[u8]) -> Self {
        let total_fills = u32::from_be_bytes([bytes[52], bytes[53], bytes[54], bytes[55]]) as usize;
        let fills_size = total_fills * RAW_LEAF_FILLS_SIZE;
        let start = RAW_LEAF_DATA_SIZE;
        let end = RAW_LEAF_DATA_SIZE + fills_size;

        let fills: Vec<Fill> = if total_fills > 0 {
            parse_fills_from_bytes(&bytes[start..end], total_fills)
        } else {
            Vec::new()
        };

        Self {
            font_style: bytes[0],
            font_size: f32::from_be_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]),
            font_weight: i32::from_be_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]),
            font_id: [
                u32::from_be_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]),
                u32::from_be_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]),
                u32::from_be_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]),
                u32::from_be_bytes([bytes[24], bytes[25], bytes[26], bytes[27]]),
            ],
            font_family: [bytes[28], bytes[29], bytes[30], bytes[31]],
            font_variant_id: [
                u32::from_be_bytes([bytes[32], bytes[33], bytes[34], bytes[35]]),
                u32::from_be_bytes([bytes[36], bytes[37], bytes[38], bytes[39]]),
                u32::from_be_bytes([bytes[40], bytes[41], bytes[42], bytes[43]]),
                u32::from_be_bytes([bytes[44], bytes[45], bytes[46], bytes[47]]),
            ],
            text_length: u32::from_be_bytes([bytes[48], bytes[49], bytes[50], bytes[51]]),
            total_fills: total_fills as u32,
            fills,
        }
    }
}

pub struct RawTextData {
    pub paragraph: Paragraph,
}

impl RawTextData {
    fn leaves_attrs_from_bytes(buffer: &[u8], num_leaves: usize) -> Vec<RawTextLeafData> {
        let mut attrs = Vec::new();
        let mut offset = 0;
        for _ in 0..num_leaves {
            let start = offset;
            let leaf_end = start + RAW_LEAF_DATA_SIZE;
            let total_fills = u32::from_be_bytes([
                buffer[leaf_end - 4],
                buffer[leaf_end - 3],
                buffer[leaf_end - 2],
                buffer[leaf_end - 1],
            ]);
            let fill_size = total_fills as usize * RAW_LEAF_FILLS_SIZE;
            let leaf_size: usize = RAW_LEAF_DATA_SIZE + fill_size;
            let end = start + leaf_size;

            let bytes: &[u8] = &buffer[start..end];
            let leaf_attrs = RawTextLeafData::from(&bytes);

            offset = end;

            attrs.push(leaf_attrs);
        }

        attrs
    }

    fn paragraph_attrs_from_bytes(buffer: &[u8]) -> RawParagraphData {
        let bytes: [u8; RAW_PARAGRAPH_DATA_SIZE] = buffer[..RAW_PARAGRAPH_DATA_SIZE]
            .try_into()
            .expect("Slice length mismatch for paragraph attributes");
        RawParagraphData::from(bytes)
    }

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

impl From<&Vec<u8>> for RawTextData {
    fn from(bytes: &Vec<u8>) -> Self {
        let num_leaves = u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]) as usize;

        let paragraph_attrs =
            RawTextData::paragraph_attrs_from_bytes(&bytes[..RAW_PARAGRAPH_DATA_SIZE]);
        let leaves_attrs =
            RawTextData::leaves_attrs_from_bytes(&bytes[1 + RAW_PARAGRAPH_DATA_SIZE..], num_leaves);

        let metadata_size = 1 + RAW_PARAGRAPH_DATA_SIZE + num_leaves * RAW_LEAF_DATA_SIZE;
        let mut text_leaves: Vec<TextLeaf> = Vec::new();
        let total_fills = leaves_attrs
            .iter()
            .map(|attrs| attrs.total_fills as usize * RAW_LEAF_FILLS_SIZE)
            .sum::<usize>();
        let mut offset = metadata_size + total_fills;

        for attrs in leaves_attrs {
            let (text, new_offset) = RawTextData::text_from_bytes(bytes, offset, attrs.text_length);
            offset = new_offset;

            let font_id = uuid_from_u32(attrs.font_id);
            let font_variant_id = uuid_from_u32(attrs.font_variant_id);

            let font_family =
                FontFamily::new(font_id, attrs.font_weight as u32, attrs.font_style.into());

            let text_leaf = TextLeaf::new(
                text,
                font_family,
                attrs.font_size,
                attrs.font_style,
                attrs.font_weight,
                font_variant_id,
                attrs.fills,
            );
            text_leaves.push(text_leaf);
        }

        let typography_ref_file = uuid_from_u32(paragraph_attrs.typography_ref_file);
        let typography_ref_id = uuid_from_u32(paragraph_attrs.typography_ref_id);

        let paragraph = Paragraph::new(
            paragraph_attrs.text_align,
            paragraph_attrs.text_decoration,
            paragraph_attrs.text_direction,
            paragraph_attrs.text_transform,
            paragraph_attrs.line_height,
            paragraph_attrs.letter_spacing,
            typography_ref_file,
            typography_ref_id,
            text_leaves.clone(),
        );

        Self { paragraph }
    }
}

pub fn auto_width(paragraphs: &Vec<Vec<skia::textlayout::Paragraph>>) -> f32 {
    paragraphs.iter().flatten().fold(0.0, |auto_width, p| {
        f32::max(p.max_intrinsic_width(), auto_width)
    })
}

pub fn auto_height(paragraphs: &Vec<Vec<skia::textlayout::Paragraph>>) -> f32 {
    paragraphs
        .iter()
        .flatten()
        .fold(0.0, |auto_height, p| auto_height + p.height())
}

fn get_text_stroke_paints(stroke: &Stroke, bounds: &Rect) -> Vec<Paint> {
    let mut paints = Vec::new();

    match stroke.kind {
        StrokeKind::InnerStroke => {
            let mut paint = skia::Paint::default();
            paint.set_blend_mode(skia::BlendMode::DstOver);
            paint.set_anti_alias(true);
            paints.push(paint);

            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Stroke);
            paint.set_blend_mode(skia::BlendMode::SrcATop);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width * 2.0);

            set_paint_fill(&mut paint, &stroke.fill, bounds);

            paints.push(paint);
        }
        StrokeKind::CenterStroke => {
            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Stroke);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width);

            set_paint_fill(&mut paint, &stroke.fill, bounds);

            paints.push(paint);
        }
        StrokeKind::OuterStroke => {
            let mut paint = skia::Paint::default();
            paint.set_style(skia::PaintStyle::Stroke);
            paint.set_blend_mode(skia::BlendMode::DstOver);
            paint.set_anti_alias(true);
            paint.set_stroke_width(stroke.width * 2.0);

            set_paint_fill(&mut paint, &stroke.fill, bounds);

            paints.push(paint);

            let mut paint = skia::Paint::default();
            paint.set_blend_mode(skia::BlendMode::Clear);
            paint.set_anti_alias(true);
            paints.push(paint);
        }
    }

    paints
}
