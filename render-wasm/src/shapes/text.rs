use crate::{
    math::Rect,
    render::{DEFAULT_EMOJI_FONT, DEFAULT_FONT},
};
use skia_safe::{
    self as skia,
    textlayout::{FontCollection, ParagraphBuilder, ParagraphStyle},
};

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
            DEFAULT_FONT.to_string(),
            DEFAULT_EMOJI_FONT.to_string(),
        ]);
        style
    }

    fn serialized_font_family(&self) -> String {
        format!("{}", self.font_family)
    }
}

#[repr(C)]
#[derive(Debug)] // Add this to derive the Debug trait
pub struct TextLeafData {
    font_style: [u8; 4],
    text_align: [u8; 4],
    text_transform: [u8; 4],
    text_decoration: [u8; 4],
    text_direction: [u8; 4],
    font_size: f32,
    line_height: f32,
    letter_spacing: f32,
    font_weight: i32,
    font_id: [u8; 4],
    font_family: [u8; 4],
    font_variant_id: [u8; 4],
    typography_ref_file: [u8; 4],
    typography_ref_id: [u8; 4],
}

impl TextLeafData {
    pub fn from_bytes(bytes: &[u8; 56]) -> Self {
        println!("TextLeafData::from_bytes: length = {}", bytes.len());
        println!("TextLeafData::from_bytes: {:?}", bytes);
        Self {
            font_style: [bytes[0], bytes[1], bytes[2], bytes[3]],
            text_align: [bytes[4], bytes[5], bytes[6], bytes[7]],
            text_transform: [bytes[8], bytes[9], bytes[10], bytes[11]],
            text_decoration: [bytes[12], bytes[13], bytes[14], bytes[15]],
            text_direction: [bytes[16], bytes[17], bytes[18], bytes[19]],
            font_size: f32::from_le_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]),
            line_height: f32::from_le_bytes([bytes[24], bytes[25], bytes[26], bytes[27]]),
            letter_spacing: f32::from_le_bytes([bytes[28], bytes[29], bytes[30], bytes[31]]),
            font_weight: i32::from_le_bytes([bytes[32], bytes[33], bytes[34], bytes[35]]),
            font_id: [bytes[36], bytes[37], bytes[38], bytes[39]],
            font_family: [bytes[40], bytes[41], bytes[42], bytes[43]],
            font_variant_id: [bytes[44], bytes[45], bytes[46], bytes[47]],
            typography_ref_file: [bytes[48], bytes[49], bytes[50], bytes[51]],
            typography_ref_id: [bytes[52], bytes[53], bytes[54], bytes[55]],
        }
    }
}
