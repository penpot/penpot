use crate::with_state;
use crate::STATE;

use crate::{
    math::Rect,
    render::{default_font, DEFAULT_EMOJI_FONT},
};
use skia_safe::{
    self as skia,
    textlayout::{Paragraph as SkiaParagraph, ParagraphBuilder, ParagraphStyle},
    Point, TextBlob,
};

use crate::skia::FontMetrics;

use super::FontFamily;
use crate::shapes::{self, merge_fills};
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

pub fn set_paragraphs_width(width: f32, paragraphs: &mut [ParagraphBuilder]) {
    for paragraph_builder in paragraphs {
        let mut paragraph = paragraph_builder.build();
        paragraph.layout(f32::MAX);
        paragraph.layout(f32::max(width, paragraph.min_intrinsic_width().ceil()));
    }
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

    pub fn to_paragraphs(&self) -> Vec<ParagraphBuilder> {
        with_state!(state, {
            let fonts = state.render_state.fonts().font_collection();

            self.paragraphs
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
                    builder
                })
                .collect()
        })
    }

    pub fn get_skia_paragraphs(&self) -> Vec<ParagraphBuilder> {
        let mut paragraphs = self.to_paragraphs();
        self.collect_paragraphs(&mut paragraphs);
        paragraphs
    }
    pub fn grow_type(&self) -> GrowType {
        self.grow_type
    }
    pub fn set_grow_type(&mut self, grow_type: GrowType) {
        self.grow_type = grow_type;
    }

    pub fn get_paths(&self, antialias: bool) -> Vec<(skia::Path, skia::Paint)> {
        let mut paths = Vec::new();

        let mut offset_y = self.bounds.y();
        let mut paragraphs = self.get_skia_paragraphs();
        for paragraph_builder in paragraphs.iter_mut() {
            // 1. Get paragraph and set the width layout
            let mut skia_paragraph = paragraph_builder.build();
            let text = paragraph_builder.get_text();
            let paragraph_width = self.bounds.width();
            skia_paragraph.layout(paragraph_width);

            let mut line_offset_y = offset_y;

            // 2. Iterate through each line in the paragraph
            for line_metrics in skia_paragraph.get_line_metrics() {
                let line_baseline = line_metrics.baseline as f32;
                let start = line_metrics.start_index;
                let end = line_metrics.end_index;

                // 3. Get styles present in line for each text leaf
                let style_metrics = line_metrics.get_style_metrics(start..end);

                let mut offset_x = 0.0;

                for (i, (start_index, style_metric)) in style_metrics.iter().enumerate() {
                    let end_index = style_metrics.get(i + 1).map_or(end, |next| next.0);

                    let start_byte = text
                        .char_indices()
                        .nth(*start_index)
                        .map(|(i, _)| i)
                        .unwrap_or(0);
                    let end_byte = text
                        .char_indices()
                        .nth(end_index)
                        .map(|(i, _)| i)
                        .unwrap_or(text.len());

                    let leaf_text = &text[start_byte..end_byte];

                    let font = skia_paragraph.get_font_at(*start_index);

                    let blob_offset_x = self.bounds.x() + line_metrics.left as f32 + offset_x;
                    let blob_offset_y = line_offset_y;

                    // 4. Get the path for each text leaf
                    if let Some((text_path, paint)) = self.generate_text_path(
                        leaf_text,
                        &font,
                        blob_offset_x,
                        blob_offset_y,
                        style_metric,
                        antialias,
                    ) {
                        let text_width = font.measure_text(leaf_text, None).0;
                        offset_x += text_width;
                        paths.push((text_path, paint));
                    }
                }
                line_offset_y = offset_y + line_baseline;
            }
            offset_y += skia_paragraph.height();
        }
        paths
    }

    fn generate_text_path(
        &self,
        leaf_text: &str,
        font: &skia::Font,
        blob_offset_x: f32,
        blob_offset_y: f32,
        style_metric: &skia::textlayout::StyleMetrics,
        antialias: bool,
    ) -> Option<(skia::Path, skia::Paint)> {
        // Convert text to path, including text decoration
        if let Some((text_blob_path, text_blob_bounds)) =
            Self::get_text_blob_path(leaf_text, font, blob_offset_x, blob_offset_y)
        {
            let mut text_path = text_blob_path.clone();
            let text_width = font.measure_text(leaf_text, None).0;

            let decoration = style_metric.text_style.decoration();
            let font_metrics = style_metric.font_metrics;

            let blob_left = blob_offset_x;
            let blob_top = blob_offset_y;
            let blob_height = text_blob_bounds.height();

            if let Some(decoration_rect) = self.calculate_text_decoration_rect(
                decoration.ty,
                font_metrics,
                blob_left,
                blob_top,
                text_width,
                blob_height,
            ) {
                text_path.add_rect(decoration_rect, None);
            }

            let mut paint = style_metric.text_style.foreground();
            paint.set_anti_alias(antialias);

            return Some((text_path, paint));
        } else {
            eprintln!("Failed to generate path for text.");
        }
        None
    }

    fn collect_paragraphs<'a>(
        &self,
        paragraphs: &'a mut Vec<ParagraphBuilder>,
    ) -> &'a mut Vec<ParagraphBuilder> {
        match self.grow_type() {
            GrowType::AutoWidth => {
                set_paragraphs_width(f32::MAX, paragraphs);
                let max_width = auto_width(paragraphs).ceil();
                set_paragraphs_width(max_width, paragraphs);
            }
            _ => {
                set_paragraphs_width(self.width(), paragraphs);
            }
        }
        paragraphs
    }

    fn calculate_text_decoration_rect(
        &self,
        decoration: skia::textlayout::TextDecoration,
        font_metrics: FontMetrics,
        blob_left: f32,
        blob_offset_y: f32,
        text_width: f32,
        blob_height: f32,
    ) -> Option<Rect> {
        match decoration {
            skia::textlayout::TextDecoration::LINE_THROUGH => {
                let underline_thickness = font_metrics.underline_thickness().unwrap_or(0.0);
                let underline_position = blob_height / 2.0;
                Some(Rect::new(
                    blob_left,
                    blob_offset_y + underline_position - underline_thickness / 2.0,
                    blob_left + text_width,
                    blob_offset_y + underline_position + underline_thickness / 2.0,
                ))
            }
            skia::textlayout::TextDecoration::UNDERLINE => {
                let underline_thickness = font_metrics.underline_thickness().unwrap_or(0.0);
                let underline_position = blob_height - underline_thickness;
                Some(Rect::new(
                    blob_left,
                    blob_offset_y + underline_position - underline_thickness / 2.0,
                    blob_left + text_width,
                    blob_offset_y + underline_position + underline_thickness / 2.0,
                ))
            }
            _ => None,
        }
    }

    fn get_text_blob_path(
        leaf_text: &str,
        font: &skia::Font,
        blob_offset_x: f32,
        blob_offset_y: f32,
    ) -> Option<(skia::Path, skia::Rect)> {
        with_state!(state, {
            let utf16_text = leaf_text.encode_utf16().collect::<Vec<u16>>();
            let text = unsafe { skia_safe::as_utf16_unchecked(&utf16_text) };
            let emoji_font = state.render_state.fonts().get_emoji_font(font.size());
            let use_font = emoji_font.as_ref().unwrap_or(font);

            if let Some(mut text_blob) = TextBlob::from_text(text, use_font) {
                let path = SkiaParagraph::get_path(&mut text_blob);
                let d = Point::new(blob_offset_x, blob_offset_y);
                let offset_path = path.with_offset(d);
                let bounds = text_blob.bounds();
                return Some((offset_path, *bounds));
            }
        });

        eprintln!("Failed to create TextBlob for text.");
        None
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

const RAW_PARAGRAPH_DATA_SIZE: usize = std::mem::size_of::<RawParagraphData>();
//const RAW_LEAF_DATA_SIZE: usize = std::mem::size_of::<RawTextLeaf>();
// FIXME
pub const RAW_LEAF_DATA_SIZE: usize = 56;
pub const RAW_LEAF_FILLS_SIZE: usize = 160;

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct RawTextLeaf {
    font_style: u8,
    font_size: f32,
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
#[derive(Debug, Clone)]
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

impl From<&[u8]> for RawTextLeafData {
    fn from(bytes: &[u8]) -> Self {
        let text_leaf: RawTextLeaf = RawTextLeaf::try_from(bytes).unwrap();
        let total_fills = text_leaf.total_fills as usize;

        let fills_size = total_fills
            .checked_mul(RAW_LEAF_FILLS_SIZE)
            .expect("Overflow occurred while calculating fills size");

        let fills_start = RAW_LEAF_DATA_SIZE;
        let fills_end = fills_start + fills_size;
        let buffer = &bytes[fills_start..fills_end];
        let fills = parse_fills_from_bytes(buffer, total_fills);

        Self {
            font_style: text_leaf.font_style,
            font_size: text_leaf.font_size,
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
                text_leaf.font_style,
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

pub fn auto_width(paragraphs: &mut [ParagraphBuilder]) -> f32 {
    paragraphs.iter_mut().fold(0.0, |auto_width, p| {
        let mut paragraph = p.build();
        paragraph.layout(f32::MAX);
        f32::max(paragraph.max_intrinsic_width(), auto_width)
    })
}

pub fn auto_height(paragraphs: &mut [ParagraphBuilder]) -> f32 {
    paragraphs.iter_mut().fold(0.0, |auto_height, p| {
        let mut paragraph = p.build();
        paragraph.layout(f32::MAX);
        auto_height + paragraph.height()
    })
}
