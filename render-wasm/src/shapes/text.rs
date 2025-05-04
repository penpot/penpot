use crate::{
    math::Rect,
    render::{default_font, DEFAULT_EMOJI_FONT},
};
use skia_safe::{
    self as skia,
    textlayout::{FontCollection, ParagraphBuilder, ParagraphStyle},
};

use super::FontFamily;
use crate::utils::uuid_from_u32;
use crate::Uuid;

#[derive(Debug, PartialEq, Clone)]
pub struct TextContent {
    paragraphs: Vec<Paragraph>,
    bounds: Rect,
}

impl TextContent {
    pub fn new(bounds: Rect) -> Self {
        let mut res = Self::default();
        res.bounds = bounds;
        res
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

    pub fn to_paragraphs(&self, fonts: &FontCollection) -> Vec<skia::textlayout::Paragraph> {
        self.paragraphs
            .iter()
            .map(|p| {
                let paragraph_style = p.paragraph_to_style();
                let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
                for leaf in &p.children {
                    let text_style = leaf.to_style(&p);
                    let text = leaf.apply_text_transform(p.text_transform);

                    builder.push_style(&text_style);
                    builder.add_text(&text);
                    builder.pop();
                }
                builder.build()
            })
            .collect()
    }

    pub fn to_skia_paragraphs(&self, fonts: &FontCollection) -> Vec<skia::textlayout::Paragraph> {
        let mut paragraphs = Vec::new();
        for mut skia_paragraph in self.to_paragraphs(fonts) {
            skia_paragraph.layout(self.width());
            paragraphs.push(skia_paragraph);
        }
        paragraphs
    }
}

impl Default for TextContent {
    fn default() -> Self {
        Self {
            paragraphs: vec![],
            bounds: Rect::default(),
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
}

impl TextLeaf {
    pub fn new(
        text: String,
        font_family: FontFamily,
        font_size: f32,
        font_style: u8,
        font_weight: i32,
        font_variant_id: Uuid,
    ) -> Self {
        Self {
            text,
            font_family,
            font_size,
            font_style,
            font_weight,
            font_variant_id,
        }
    }

    pub fn to_style(&self, paragraph: &Paragraph) -> skia::textlayout::TextStyle {
        let mut style = skia::textlayout::TextStyle::default();
        style.set_color(skia::Color::BLACK);
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

pub const RAW_PARAGRAPH_DATA_SIZE: usize = 48;
pub const RAW_LEAF_DATA_SIZE: usize = 52;

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

pub struct RawTextData {
    pub paragraph: Paragraph,
}

impl RawTextData {
    fn leaves_attrs_from_bytes(buffer: &[u8], num_leaves: usize) -> Vec<RawTextLeafData> {
        let mut attrs = Vec::new();
        for i in 0..num_leaves {
            let start = i * RAW_LEAF_DATA_SIZE;
            let end = start + RAW_LEAF_DATA_SIZE;
            let bytes = &buffer[start..end];
            let array: [u8; RAW_LEAF_DATA_SIZE] = bytes.try_into().expect("Slice length mismatch");
            let leaf_attrs = RawTextLeafData::from(array);
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
        let text = String::from_utf8(text_utf8).expect("Invalid UTF-8 text");

        (text, text_end)
    }
}

impl From<[u8; RAW_LEAF_DATA_SIZE]> for RawTextLeafData {
    fn from(bytes: [u8; RAW_LEAF_DATA_SIZE]) -> Self {
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
        }
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
        let text_start = metadata_size;
        let mut offset = text_start;
        let mut text_leaves: Vec<TextLeaf> = Vec::new();

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
