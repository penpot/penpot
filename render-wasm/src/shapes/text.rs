use crate::{
    math::Rect,
    render::{default_font, DEFAULT_EMOJI_FONT},
};
use skia_safe::{
    self as skia, sampling_options,
    textlayout::{Paragraph as SkiaParagraph, ParagraphBuilder, ParagraphStyle},
    FontMetrics, Point, Shader, TextBlob,
};
use unicode_segmentation::UnicodeSegmentation;
use crate::shapes::VerticalAlign;
use std::{collections::HashSet, thread::current};

use super::FontFamily;
use crate::shapes::{self, merge_fills};
use crate::utils::{get_fallback_fonts, uuid_from_u32};
use crate::wasm::fills::parse_fills_from_bytes;
use crate::Uuid;

use crate::with_state_mut;
use crate::STATE;

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
    pub vertical_align: VerticalAlign,
}

pub fn set_paragraphs_width(width: f32, paragraphs: &mut [ParagraphBuilder]) {
    for paragraph_builder in paragraphs {
        let mut paragraph = paragraph_builder.build();
        paragraph.layout(f32::MAX);
        paragraph.layout(f32::max(width, paragraph.min_intrinsic_width().ceil()));
    }
}

impl TextContent {
    pub fn new(bounds: Rect, grow_type: GrowType, vertical_align: VerticalAlign) -> Self {
        Self {
            paragraphs: Vec::new(),
            bounds,
            grow_type,
            vertical_align,
        }
    }

    pub fn new_bounds(&self, bounds: Rect) -> Self {
        let paragraphs = self.paragraphs.clone();
        let grow_type = self.grow_type;
        let vertical_align = self.vertical_align;

        Self {
            paragraphs,
            bounds,
            grow_type,
            vertical_align
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
        with_state_mut!(state, {
            let fallback_fonts = get_fallback_fonts();
            let fonts = state.render_state().fonts().font_collection();

            self.paragraphs
                .iter()
                .map(|p| {
                    let paragraph_style = p.paragraph_to_style();
                    let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
                    for leaf in &p.children {
                        let text_style = leaf.to_style(p, &self.bounds, fallback_fonts);
                        let text = leaf.apply_text_transform();
                        builder.push_style(&text_style);
                        builder.add_text(&text);
                        builder.pop();
                    }
                    builder
                })
                .collect()
        })
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

    pub fn get_skia_paragraphs(&self) -> Vec<ParagraphBuilder> {
        let mut paragraphs = self.to_paragraphs();
        self.collect_paragraphs(&mut paragraphs);
        paragraphs
    }

    pub fn get_paths(&self, antialias: bool) -> Vec<(skia::Path, skia::Paint)> {
        let mut paths = Vec::new();

        // let mut offset_y = self.bounds.y();
        let mut paragraphs = self.get_skia_paragraphs();

        let total_paragraphs_height: f32 = paragraphs
            .iter_mut()
            .map(|p| {
                let mut skia_paragraph = p.build();
                skia_paragraph.layout(self.bounds.width());
                skia_paragraph.height()
            })
            .sum();
        let container_height = self.bounds.height();
        let mut offset_y = match self.vertical_align {
            VerticalAlign::Center => (container_height - total_paragraphs_height) / 2.0,
            VerticalAlign::Bottom => container_height - total_paragraphs_height,
            _ => 0.0,
        } + self.bounds.y();

        for paragraph_builder in paragraphs.iter_mut() {
            // 1. Get paragraph and set the width layout
            let mut skia_paragraph = paragraph_builder.build();
            let text = paragraph_builder.get_text();
            let paragraph_width = self.bounds.width();
            skia_paragraph.layout(paragraph_width);

            let mut line_offset_y = offset_y;

            let lines = skia_paragraph.get_line_metrics().len();
            println!("### Text {}", text);
            let graphemes: Vec<&str> = UnicodeSegmentation::graphemes(text, true).collect();
            println!("### Graphemes: {:?}", graphemes);

            let mut index = 0;
            for line_metrics in skia_paragraph.get_line_metrics() {
                let mut start = line_metrics.start_index;
                let end = line_metrics.end_index - 1;
                let line_baseline = line_metrics.baseline as f32;
                println!("### Line metrics: start: {}, end: {}", start, end);
                let mut offset_x = 0.0;
                let mut current_text_width = 0.0;
                start = if start == 0 { start } else { start - 1 };

                let line_text = graphemes[start..end].concat();
                let line_str = line_text.as_str();
                println!("### Line text: '{}'", line_text);

                let line_graphemes = UnicodeSegmentation::graphemes(line_str, true).collect::<Vec<&str>>();
                let line_style_metrics = line_metrics.get_style_metrics(start..end);
                // println!("### Style metrics: {:?}", line_style_metrics);
                

                for (grapheme_index, grapheme) in line_graphemes.iter().enumerate() {
                    println!("### Grapheme {}: '{}', len: {}", grapheme_index, grapheme, grapheme.len());
                    
                    // Find the style that applies to this grapheme_index
                    // We want the style with the highest start index that is <= grapheme_index
                    let style_metric = line_style_metrics
                        .iter()
                        .filter(|(start_idx, _)| *start_idx <= grapheme_index)
                        .last()
                        .map(|(_, metrics)| metrics)
                        .unwrap_or(&line_style_metrics[0].1);
                    // println!("### Style metrics: {:?}", style_metric);
                    println!("### index: {}", index);
                    let font = skia_paragraph.get_font_at(index);
                    index += grapheme.len();
                    println!("### Font: {:?}", font.typeface());
                    let blob_offset_x = self.bounds.x() + line_metrics.left as f32 + offset_x;
                    let blob_offset_y = line_offset_y;

                    // 4. Get the path for each text leaf
                    if let Some((text_path, paint)) = self.generate_text_path(
                        &grapheme,
                        &font,
                        blob_offset_x,
                        blob_offset_y,
                        &self.bounds,
                        &style_metric,
                        antialias,
                    ) {
                        let text_width = font.measure_text(grapheme, None).0;
                        offset_x += text_width;
                        current_text_width = text_width;
                        paths.push((text_path, paint));
                    }
                }
                line_offset_y = offset_y + line_baseline;
            }
            offset_y += skia_paragraph.height();

            // 2. Iterate through each line in the paragraph
            // for line_metrics in skia_paragraph.get_line_metrics() {
            //     let line_baseline = line_metrics.baseline as f32;
            //     let start = line_metrics.start_index;
            //     let end = line_metrics.end_index - 1;
            //     // let line_text: String = text.chars().skip(start).take(end - start).collect();
            //     let line_text = graphemes[start..end].concat();
            //     // println!("### Line text: '{}'", line_text);
            //     // 3. Get styles present in line for each text leaf
            //     let style_metrics = line_metrics.get_style_metrics(start..end);
            //     let mut offset_x = 0.0;

            //     for (i, (start_index, style_metric)) in style_metrics.iter().enumerate() {
            //         let mut end_index = 0;
            //         if i < style_metrics.len() - 1 {
            //             end_index = style_metrics[i + 1].0;
            //         } else {
            //             end_index = line_text.len();
            //         }

            //         let leaf_text = graphemes[*start_index..end_index].concat();
            //         println!("### Leaf text: '{}'", leaf_text);
            //         let font = skia_paragraph.get_font_at(*start_index);

            //         let blob_offset_x = self.bounds.x() + line_metrics.left as f32 + offset_x;
            //         let blob_offset_y = line_offset_y;

            //         // 4. Get the path for each text leaf
            //         if let Some((text_path, paint)) = self.generate_text_path(
            //             &leaf_text,
            //             &font,
            //             blob_offset_x,
            //             blob_offset_y,
            //             &self.bounds,
            //             style_metric,
            //             antialias,
            //         ) {
            //             let text_width = font.measure_text(leaf_text, None).0;
            //             offset_x += text_width;
            //             paths.push((text_path, paint));
            //         }
            //     }
            //     line_offset_y = offset_y + line_baseline;
            // }
            // offset_y += skia_paragraph.height();
        }
        paths
    }

    fn generate_text_path(
        &self,
        leaf_text: &str,
        font: &skia::Font,
        blob_offset_x: f32,
        blob_offset_y: f32,
        bounds: &skia::Rect,
        style_metric: &skia::textlayout::StyleMetrics,
        antialias: bool,
    ) -> Option<(skia::Path, skia::Paint)> {
        // Convert text to path, including text decoration
        // TextBlob might be empty and, in this case, we return None
        // This is used to avoid rendering empty paths, but we can
        // revisit this logic later
        let mut foreground_paint = style_metric.text_style.foreground();
        foreground_paint.set_anti_alias(antialias);

        if let Some((text_blob_path, text_blob_bounds, paint)) =
            // Self::get_text_image_path(leaf_text, font, blob_offset_x, blob_offset_y, &bounds, foreground_paint)
            Self::get_text_blob_path(leaf_text, font, blob_offset_x, blob_offset_y, &bounds)
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

            return Some((text_path, foreground_paint));
            // return Some((text_path, paint));
        }
        None
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

    fn get_text_image_path(
        leaf_text: &str,
        font: &skia::Font,
        blob_offset_x: f32,
        blob_offset_y: f32,
        blob_bounds: &skia::Rect,
        foreground_paint: skia::Paint,
    ) -> Option<(skia::Path, skia::Rect, skia::Paint)> {
        let scale_factor = 1.0; // FIXME: improve emoji image resolution. This should be calculated taking into account the zoom level
        let width = (blob_bounds.width() * scale_factor).ceil() as i32;
        let height = (blob_bounds.height() * scale_factor).ceil() as i32;

        let mut surface = create_emoji_surface(width, height)?;
        let canvas = surface.canvas();
        canvas.clear(skia::Color::TRANSPARENT);

        let mut sampled_font = font.clone();
        sampled_font.set_size(font.size() * scale_factor);

        let (_, metrics) = sampled_font.metrics();
        let baseline = -metrics.ascent as f32;
        canvas.draw_str(
            leaf_text,
            skia_safe::Point::new(0.0, baseline),
            &sampled_font,
            &foreground_paint,
        );

        let image = surface.image_snapshot();

        let mut rect = skia_safe::Rect::from_xywh(
            blob_offset_x,
            blob_offset_y,
            blob_bounds.width(),
            blob_bounds.height(),
        );

        let mut path = skia_safe::Path::new();
        path.add_rect(&mut rect, None);

        let mut image_paoint = skia_safe::Paint::default();
        let shader = skia_safe::Image::to_raw_shader(
            &image,
            (skia_safe::TileMode::Clamp, skia_safe::TileMode::Clamp),
            skia::SamplingOptions::new(skia::FilterMode::Linear, skia::MipmapMode::Nearest),
            &Some(skia_safe::Matrix::translate((rect.left, rect.top))),
        );
        image_paoint.set_shader(shader);
        Some((path, *blob_bounds, image_paoint))    }

    fn get_text_blob_path(
        leaf_text: &str,
        font: &skia::Font,
        blob_offset_x: f32,
        blob_offset_y: f32,
        _blob_bounds: &skia::Rect,
    ) -> Option<(skia::Path, skia::Rect, skia::Paint)> {
        let utf16_text = leaf_text.encode_utf16().collect::<Vec<u16>>();
        let text = unsafe { skia_safe::as_utf16_unchecked(&utf16_text) };

        if let Some(mut text_blob) = TextBlob::from_text(text, font) {
            let path = SkiaParagraph::get_path(&mut text_blob);
            let d = Point::new(blob_offset_x, blob_offset_y);
            let offset_path = path.with_offset(d);
            let bounds = text_blob.bounds();
            return Some((offset_path, *bounds, skia::Paint::default()));
        }
        None
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
            vertical_align: VerticalAlign::Top,
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
    font_style: u8,
    font_weight: i32,
    font_variant_id: Uuid,
    text_decoration: u8,
    text_transform: u8,
    fills: Vec<shapes::Fill>,
}

impl TextLeaf {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        text: String,
        font_family: FontFamily,
        font_size: f32,
        font_style: u8,
        text_decoration: u8,
        text_transform: u8,
        font_weight: i32,
        font_variant_id: Uuid,
        fills: Vec<shapes::Fill>,
    ) -> Self {
        Self {
            text,
            font_family,
            font_size,
            font_style,
            text_decoration,
            text_transform,
            font_weight,
            font_variant_id,
            fills,
        }
    }

    pub fn to_style(
        &self,
        paragraph: &Paragraph,
        content_bounds: &Rect,
        fallback_fonts: &HashSet<String>,
    ) -> skia::textlayout::TextStyle {
        let mut style = skia::textlayout::TextStyle::default();

        let paint = merge_fills(&self.fills, *content_bounds);
        style.set_foreground_paint(&paint);
        style.set_font_size(self.font_size);
        style.set_letter_spacing(paragraph.letter_spacing);
        style.set_height(paragraph.line_height);
        style.set_height_override(true);
        style.set_decoration_type(match self.text_decoration {
            0 => skia::textlayout::TextDecoration::NO_DECORATION,
            1 => skia::textlayout::TextDecoration::UNDERLINE,
            2 => skia::textlayout::TextDecoration::LINE_THROUGH,
            3 => skia::textlayout::TextDecoration::OVERLINE,
            _ => skia::textlayout::TextDecoration::NO_DECORATION,
        });

        // FIXME fix decoration styles
        style.set_decoration_color(paint.color());

        let mut font_families = vec![
            self.serialized_font_family(),
            default_font(),
            DEFAULT_EMOJI_FONT.to_string(),
        ];

        font_families.extend(fallback_fonts.iter().cloned());
        style.set_font_families(&font_families);

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
    text_decoration: u8,
    text_transform: u8,
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
                text_leaf.text_decoration,
                text_leaf.text_transform,
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

pub fn max_width(paragraphs: &mut [ParagraphBuilder]) -> f32 {
    paragraphs.iter_mut().fold(0.0, |max_width, p| {
        let mut paragraph = p.build();
        paragraph.layout(f32::MAX);
        f32::max(paragraph.max_width(), max_width)
    })
}

pub fn auto_height(paragraphs: &mut [ParagraphBuilder]) -> f32 {
    paragraphs.iter_mut().fold(0.0, |auto_height, p| {
        let mut paragraph = p.build();
        paragraph.layout(f32::MAX);
        auto_height + paragraph.height()
    })
}

fn create_emoji_surface(width: i32, height: i32) -> Option<skia_safe::Surface> {
    skia_safe::surfaces::raster_n32_premul(skia_safe::ISize::new(width, height))
}
