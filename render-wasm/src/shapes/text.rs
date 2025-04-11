use crate::utils::uuid_from_u32_quartet;
use crate::{
    math::Rect,
    render::{default_font, DEFAULT_EMOJI_FONT},
};
use skia_safe::{
    self as skia,
    textlayout::{FontCollection, ParagraphBuilder, ParagraphStyle},
};
use uuid::Uuid;

use super::FontFamily;

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

    pub fn add_paragraph(&mut self) {
        let p = Paragraph::default();
        self.paragraphs.push(p);
    }

    pub fn add_leaf(
        &mut self,
        text: String,
        font_family: FontFamily,
        font_size: f32,
    ) -> Result<(), String> {
        let paragraph = self
            .paragraphs
            .last_mut()
            .ok_or("No paragraph to add text leaf to")?;

        paragraph.add_leaf(TextLeaf::new(text, font_family, font_size));

        Ok(())
    }

    pub fn push_paragraph(&mut self, paragraph: Paragraph) -> Result<(), String> {
        self.paragraphs.push(paragraph);
        Ok(())
    }

    pub fn to_paragraphs(&self, fonts: &FontCollection) -> Vec<skia::textlayout::Paragraph> {
        let mut paragraph_style = ParagraphStyle::default();
        // TODO: read text direction, align, etc. from the shape
        paragraph_style.set_text_direction(skia::textlayout::TextDirection::LTR);

        self.paragraphs
            .iter()
            .map(|p| {
                let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);

                for leaf in &p.children {
                    let text_style = leaf.to_style();
                    builder.push_style(&text_style);
                    builder.add_text(&leaf.text);
                    builder.pop();
                }

                builder.build()
            })
            .collect()
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
    children: Vec<TextLeaf>,
}

impl Default for Paragraph {
    fn default() -> Self {
        Self { children: vec![] }
    }
}

impl Paragraph {
    fn add_leaf(&mut self, leaf: TextLeaf) {
        self.children.push(leaf);
    }
}

#[derive(Debug, PartialEq, Clone)]
pub struct TextLeaf {
    text: String,
    font_family: FontFamily,
    font_size: f32,
}

impl TextLeaf {
    pub fn new(text: String, font_family: FontFamily, font_size: f32) -> Self {
        Self {
            text,
            font_family,
            font_size,
        }
    }

    pub fn to_style(&self) -> skia::textlayout::TextStyle {
        let mut style = skia::textlayout::TextStyle::default();
        style.set_color(skia::Color::BLACK);
        style.set_font_size(self.font_size);
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
}

#[repr(C)]
#[derive(Debug)]
pub struct TextLeafData {
    font_style: u8,
    text_align: u8,
    text_transform: u8,
    text_decoration: u8,
    text_direction: u8,
    font_size: f32,
    line_height: f32,
    letter_spacing: f32,
    font_weight: i32,
    font_id: [u8; 4],
    font_family: [u8; 4],
    font_variant_id: [u8; 4],
    typography_ref_file: [u8; 4],
    typography_ref_id: [u8; 4],
    text_length: u32,
}

impl TextLeafData {
    pub fn from_bytes(bytes: &[u8; 48]) -> Self {
        Self {
            font_style: bytes[0],
            text_align: bytes[1],
            text_transform: bytes[2],
            text_decoration: bytes[3],
            text_direction: bytes[4],
            font_size: f32::from_le_bytes([bytes[5], bytes[6], bytes[7], bytes[8]]),
            line_height: f32::from_le_bytes([bytes[9], bytes[10], bytes[11], bytes[12]]),
            letter_spacing: f32::from_le_bytes([bytes[13], bytes[14], bytes[15], bytes[16]]),
            font_weight: i32::from_le_bytes([bytes[17], bytes[18], bytes[19], bytes[20]]),
            font_id: [bytes[21], bytes[22], bytes[23], bytes[24]],
            font_family: [bytes[25], bytes[26], bytes[27], bytes[28]],
            font_variant_id: [bytes[29], bytes[30], bytes[31], bytes[32]],
            typography_ref_file: [bytes[33], bytes[34], bytes[35], bytes[36]],
            typography_ref_id: [bytes[37], bytes[38], bytes[39], bytes[40]],
            text_length: u32::from_le_bytes([bytes[41], bytes[42], bytes[43], bytes[44]]),
        }
    }

    pub fn parse_leaves(buffer: &[u8], num_leaves: usize) -> Vec<Paragraph> {
        let mut paragraphs: Vec<Paragraph> = Vec::new();
        let leaf_attr_size = 48;
        let metadata_size = num_leaves * leaf_attr_size;

        // Parse leaf data attrs
        let mut leaves_metadata = Vec::new();
        for i in 0..num_leaves {
            let start = i * leaf_attr_size;
            let end = start + leaf_attr_size;
            let leaf_data = TextLeafData::from_bytes(
                buffer[start..end]
                    .try_into()
                    .expect("Invalid metadata size"),
            );
            leaves_metadata.push(leaf_data);
        }

        // Parse text
        let text_start = metadata_size;
        let mut offset = text_start;

        for leaf_data in leaves_metadata {
            let text_length = leaf_data.text_length as usize;
            let text_end = offset + text_length;
            let text_utf8 = buffer[offset..text_end].to_vec();
            let text = String::from_utf8(text_utf8).expect("Invalid UTF-8 text");
            offset = text_end;

            println!("leaf data: {:?}", leaf_data);
            println!("text: {:?}", text);

            let font_id = uuid_from_u32_quartet(
                leaf_data.font_id[0].into(),
                leaf_data.font_id[1].into(),
                leaf_data.font_id[2].into(),
                leaf_data.font_id[3].into(),
            );

            // TODO review
            let mut paragraph = Paragraph::default();
            paragraph.add_leaf(TextLeaf {
                text,
                font_family: FontFamily::new(
                    font_id,
                    leaf_data.font_weight as u32,
                    leaf_data.font_style.into(),
                ),
                font_size: leaf_data.font_size,
            });

            paragraphs.push(paragraph);
        }

        paragraphs
    }
}
