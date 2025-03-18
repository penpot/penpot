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

    pub fn width(&self) -> f32 {
        self.bounds.width()
    }

    pub fn x(&self) -> f32 {
        self.bounds.x()
    }

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
