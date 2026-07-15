use macros::{wasm_error, ToJs};

use super::{fills::RawFillData, fonts::RawFontStyle};

use crate::mem::{self, SerializableResult};
use crate::shapes::{
    self, AnnotationClearance, FontFeatures, GrowType, RubyAlign, RubyOverhang, RubySide, RubySize,
    Shape, TextAlign, TextCombineUpright, TextDecoration, TextDirection, TextEmphasis,
    TextTransform, Type,
};
use crate::utils::{uuid_from_u32, uuid_from_u32_quartet};
use crate::{with_current_shape, with_current_shape_mut, with_state};

use crate::error::Error;

pub mod helpers;

const RAW_SPAN_DATA_SIZE: usize = std::mem::size_of::<RawTextSpan>();
const RAW_PARAGRAPH_DATA_SIZE: usize = std::mem::size_of::<RawParagraphData>();

const MAX_TEXT_FILLS: usize = 8;

// CHANGEME: Move all the types from japanes text layout to its own module

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
pub enum RawTextAlign {
    Left = 0,
    Center = 1,
    Right = 2,
    Justify = 3,
}

impl From<RawTextAlign> for TextAlign {
    fn from(value: RawTextAlign) -> Self {
        match value {
            RawTextAlign::Left => TextAlign::Left,
            RawTextAlign::Center => TextAlign::Center,
            RawTextAlign::Right => TextAlign::Right,
            RawTextAlign::Justify => TextAlign::Justify,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
pub enum RawTextDirection {
    Ltr = 0,
    Rtl = 1,
}

impl From<RawTextDirection> for TextDirection {
    fn from(value: RawTextDirection) -> Self {
        match value {
            RawTextDirection::Ltr => TextDirection::LTR,
            RawTextDirection::Rtl => TextDirection::RTL,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawWritingMode {
    HorizontalTb = 0,
    VerticalRl = 1,
}

impl From<RawWritingMode> for shapes::WritingMode {
    fn from(value: RawWritingMode) -> Self {
        match value {
            RawWritingMode::HorizontalTb => shapes::WritingMode::HorizontalTb,
            RawWritingMode::VerticalRl => shapes::WritingMode::VerticalRl,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawTextOrientation {
    Mixed = 0,
    Upright = 1,
}

impl From<RawTextOrientation> for shapes::TextOrientation {
    fn from(value: RawTextOrientation) -> Self {
        match value {
            RawTextOrientation::Mixed => shapes::TextOrientation::Mixed,
            RawTextOrientation::Upright => shapes::TextOrientation::Upright,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawTextCombineUpright {
    None = 0,
    All = 1,
    Digits = 2,
    Digits2 = 3,
    Digits3 = 4,
}

impl From<RawTextCombineUpright> for TextCombineUpright {
    fn from(value: RawTextCombineUpright) -> Self {
        match value {
            RawTextCombineUpright::None => TextCombineUpright::None,
            RawTextCombineUpright::All => TextCombineUpright::All,
            RawTextCombineUpright::Digits => TextCombineUpright::Digits,
            RawTextCombineUpright::Digits2 => TextCombineUpright::Digits2,
            RawTextCombineUpright::Digits3 => TextCombineUpright::Digits3,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawTextEmphasis {
    None = 0,
    FilledDot = 1,
    OpenDot = 2,
    FilledCircle = 3,
    OpenCircle = 4,
    FilledSesame = 5,
    OpenSesame = 6,
}

impl From<RawTextEmphasis> for TextEmphasis {
    fn from(value: RawTextEmphasis) -> Self {
        match value {
            RawTextEmphasis::None => TextEmphasis::None,
            RawTextEmphasis::FilledDot => TextEmphasis::FilledDot,
            RawTextEmphasis::OpenDot => TextEmphasis::OpenDot,
            RawTextEmphasis::FilledCircle => TextEmphasis::FilledCircle,
            RawTextEmphasis::OpenCircle => TextEmphasis::OpenCircle,
            RawTextEmphasis::FilledSesame => TextEmphasis::FilledSesame,
            RawTextEmphasis::OpenSesame => TextEmphasis::OpenSesame,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawWarichu {
    None = 0,
    Warichu = 1,
}

impl From<RawWarichu> for bool {
    fn from(value: RawWarichu) -> Self {
        value == RawWarichu::Warichu
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawFontFeatures {
    None = 0,
    Palt = 1,
    Vpal = 2,
}

impl From<RawFontFeatures> for FontFeatures {
    fn from(value: RawFontFeatures) -> Self {
        match value {
            RawFontFeatures::None => FontFeatures::None,
            RawFontFeatures::Palt => FontFeatures::Palt,
            RawFontFeatures::Vpal => FontFeatures::Vpal,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawAnnotationClearance {
    None = 0,
    Auto = 1,
}

impl From<RawAnnotationClearance> for AnnotationClearance {
    fn from(value: RawAnnotationClearance) -> Self {
        match value {
            RawAnnotationClearance::None => AnnotationClearance::None,
            RawAnnotationClearance::Auto => AnnotationClearance::Auto,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawRubySize {
    Half = 0,
    Third = 1,
    Quarter = 2,
}

impl From<RawRubySize> for RubySize {
    fn from(value: RawRubySize) -> Self {
        match value {
            RawRubySize::Half => RubySize::Half,
            RawRubySize::Third => RubySize::Third,
            RawRubySize::Quarter => RubySize::Quarter,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawRubyAlign {
    SpaceAround = 0,
    Center = 1,
    Start = 2,
    SpaceBetween = 3,
}

impl From<RawRubyAlign> for RubyAlign {
    fn from(value: RawRubyAlign) -> Self {
        match value {
            RawRubyAlign::SpaceAround => RubyAlign::SpaceAround,
            RawRubyAlign::Center => RubyAlign::Center,
            RawRubyAlign::Start => RubyAlign::Start,
            RawRubyAlign::SpaceBetween => RubyAlign::SpaceBetween,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawRubyOverhang {
    Auto = 0,
    None = 1,
}

impl From<RawRubyOverhang> for RubyOverhang {
    fn from(value: RawRubyOverhang) -> Self {
        match value {
            RawRubyOverhang::Auto => RubyOverhang::Auto,
            RawRubyOverhang::None => RubyOverhang::None,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawRubySide {
    Over = 0,
    Under = 1,
}

impl From<RawRubySide> for RubySide {
    fn from(value: RawRubySide) -> Self {
        match value {
            RawRubySide::Over => RubySide::Over,
            RawRubySide::Under => RubySide::Under,
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
pub enum RawTextDecoration {
    None = 0,
    Underline = 1,
    LineThrough = 2,
    Overline = 3,
}

impl From<RawTextDecoration> for Option<TextDecoration> {
    fn from(value: RawTextDecoration) -> Self {
        match value {
            RawTextDecoration::None => None,
            RawTextDecoration::Underline => Some(TextDecoration::UNDERLINE),
            RawTextDecoration::LineThrough => Some(TextDecoration::LINE_THROUGH),
            RawTextDecoration::Overline => Some(TextDecoration::OVERLINE),
        }
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
pub enum RawTextTransform {
    None = 0,
    Uppercase = 1,
    Lowercase = 2,
    Capitalize = 3,
}

impl From<RawTextTransform> for Option<TextTransform> {
    fn from(value: RawTextTransform) -> Self {
        match value {
            RawTextTransform::None => None,
            RawTextTransform::Uppercase => Some(TextTransform::Uppercase),
            RawTextTransform::Lowercase => Some(TextTransform::Lowercase),
            RawTextTransform::Capitalize => Some(TextTransform::Capitalize),
        }
    }
}

#[repr(C)]
#[repr(align(4))]
#[derive(Debug, Clone, Copy)]
pub struct RawParagraphData {
    span_count: u32,
    text_align: RawTextAlign,
    text_direction: RawTextDirection,
    text_decoration: RawTextDecoration,
    text_transform: RawTextTransform,
    writing_mode: RawWritingMode,
    text_orientation: RawTextOrientation,
    // Explicit padding so the CLJS writer and this struct agree on a
    // 4-byte-aligned layout; always written as zero.
    _padding: [u8; 2],
    line_height: f32,
    letter_spacing: f32,
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

#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct RawTextSpan {
    font_style: RawFontStyle,
    text_decoration: RawTextDecoration,
    text_transform: RawTextTransform,
    text_direction: RawTextDirection,
    text_orientation: RawTextOrientation,
    text_combine_upright: RawTextCombineUpright,
    text_emphasis: RawTextEmphasis,
    warichu: RawWarichu,
    font_features: RawFontFeatures,
    annotation_clearance: RawAnnotationClearance,
    ruby_size: RawRubySize,
    ruby_align: RawRubyAlign,
    ruby_overhang: RawRubyOverhang,
    ruby_side: RawRubySide,
    // Explicit padding so the CLJS writer and this struct agree on a
    // 4-byte-aligned layout; always written as zero.
    _padding: [u8; 2],
    font_size: f32,
    line_height: f32,
    letter_spacing: f32,
    font_weight: i32,
    font_id: [u32; 4],
    font_family: [u8; 4],
    font_variant_id: [u32; 4], // TODO: maybe add RawUUID type
    text_length: u32,
    ruby_length: u32,
    fill_count: u32,
    fills: [RawFillData; MAX_TEXT_FILLS],
}

impl From<[u8; RAW_SPAN_DATA_SIZE]> for RawTextSpan {
    fn from(bytes: [u8; RAW_SPAN_DATA_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl TryFrom<&[u8]> for RawTextSpan {
    type Error = String;
    fn try_from(bytes: &[u8]) -> Result<Self, Self::Error> {
        let data: [u8; RAW_SPAN_DATA_SIZE] = bytes
            .get(0..RAW_SPAN_DATA_SIZE)
            .and_then(|slice| slice.try_into().ok())
            .ok_or("Invalid text span data".to_string())?;
        Ok(RawTextSpan::from(data))
    }
}

impl From<RawTextSpan> for shapes::TextSpan {
    fn from(value: RawTextSpan) -> Self {
        let text = String::default();

        let font_family = shapes::FontFamily::new(
            uuid_from_u32(value.font_id),
            value.font_weight as u32,
            value.font_style.into(),
        );

        let fills = value
            .fills
            .into_iter()
            .take(value.fill_count as usize)
            .map(|fill| fill.into())
            .collect();

        let mut span = Self::new(
            text,
            font_family,
            value.font_size,
            value.line_height,
            value.letter_spacing,
            value.text_decoration.into(),
            value.text_transform.into(),
            value.text_direction.into(),
            value.font_weight,
            uuid_from_u32(value.font_variant_id),
            fills,
        );
        span.set_text_orientation(value.text_orientation.into());
        span.set_text_combine_upright(value.text_combine_upright.into());
        span.set_text_emphasis(value.text_emphasis.into());
        span.set_warichu(value.warichu.into());
        span.set_font_features(value.font_features.into());
        span.set_annotation_clearance(value.annotation_clearance.into());
        span.set_ruby_size(value.ruby_size.into());
        span.set_ruby_align(value.ruby_align.into());
        span.set_ruby_overhang(value.ruby_overhang.into());
        span.set_ruby_side(value.ruby_side.into());
        span
    }
}

#[repr(C)]
#[derive(Debug, Clone)]
pub struct RawParagraph {
    attrs: RawParagraphData,
    spans: Vec<RawTextSpan>,
    text_buffer: Vec<u8>,
}

impl TryFrom<&Vec<u8>> for RawParagraph {
    // TODO: use a proper error type
    type Error = String;

    fn try_from(bytes: &Vec<u8>) -> Result<Self, Self::Error> {
        let attrs = RawParagraphData::try_from(&bytes[..RAW_PARAGRAPH_DATA_SIZE])?;
        let mut offset = RAW_PARAGRAPH_DATA_SIZE;
        let mut raw_text_spans: Vec<RawTextSpan> = Vec::new();

        for _ in 0..attrs.span_count {
            let text_span = RawTextSpan::try_from(&bytes[offset..(offset + RAW_SPAN_DATA_SIZE)])?;
            offset += RAW_SPAN_DATA_SIZE;
            raw_text_spans.push(text_span);
        }

        let text_buffer = &bytes[offset..];

        Ok(Self {
            attrs,
            spans: raw_text_spans,
            text_buffer: text_buffer.to_vec(),
        })
    }
}

impl From<RawParagraph> for shapes::Paragraph {
    fn from(value: RawParagraph) -> Self {
        let mut spans = vec![];

        // Layout: [<all span texts> <all span ruby texts>]. Annotation blobs
        // begin after all base text.
        let mut offset = 0;
        let mut ruby_offset: usize = value.spans.iter().map(|s| s.text_length as usize).sum();
        for raw_span in value.spans.into_iter() {
            let delta = raw_span.text_length as usize;
            let text_buffer = value.text_buffer.get(offset..offset + delta).unwrap_or(&[]);
            let ruby_delta = raw_span.ruby_length as usize;
            let ruby_buffer = value
                .text_buffer
                .get(ruby_offset..ruby_offset + ruby_delta)
                .unwrap_or(&[]);
            let mut span = shapes::TextSpan::from(raw_span);
            if !text_buffer.is_empty() {
                span.set_text(String::from_utf8_lossy(text_buffer).to_string());
            }
            if !ruby_buffer.is_empty() {
                span.set_ruby(String::from_utf8_lossy(ruby_buffer).to_string());
            }
            spans.push(span);
            offset += delta;
            ruby_offset += ruby_delta;
        }

        let mut paragraph = shapes::Paragraph::new(
            value.attrs.text_align.into(),
            value.attrs.text_direction.into(),
            value.attrs.text_decoration.into(),
            value.attrs.text_transform.into(),
            value.attrs.line_height,
            value.attrs.letter_spacing,
            spans,
        );
        paragraph.set_writing_mode(value.attrs.writing_mode.into());
        paragraph.set_text_orientation(value.attrs.text_orientation.into());
        paragraph
    }
}

#[derive(Debug, PartialEq, Clone, Copy, ToJs)]
#[repr(u8)]
#[allow(dead_code)]
pub enum RawGrowType {
    Fixed = 0,
    AutoWidth = 1,
    AutoHeight = 2,
}

impl From<u8> for RawGrowType {
    fn from(value: u8) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl From<RawGrowType> for GrowType {
    fn from(value: RawGrowType) -> Self {
        match value {
            RawGrowType::Fixed => GrowType::Fixed,
            RawGrowType::AutoWidth => GrowType::AutoWidth,
            RawGrowType::AutoHeight => GrowType::AutoHeight,
        }
    }
}

#[no_mangle]
pub extern "C" fn clear_shape_text() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.clear_text();
    });
}

#[no_mangle]
#[wasm_error]
pub extern "C" fn set_shape_text_content() -> crate::error::Result<()> {
    let bytes = mem::bytes();
    let raw_text_data = RawParagraph::try_from(&bytes)
        .map_err(|_| Error::CriticalError("Invalid text data".to_string()))?;

    with_current_shape_mut!(state, |shape: &mut Shape| {
        shape.add_paragraph(raw_text_data.into()).map_err(|_| {
            Error::RecoverableError(format!(
                "Error with set_shape_text_content on {:?}",
                shape.id
            ))
        })?;
    });

    mem::free_bytes()?;
    Ok(())
}

#[no_mangle]
pub extern "C" fn set_shape_grow_type(grow_type: u8) {
    let grow_type = RawGrowType::from(grow_type);
    with_current_shape_mut!(state, |shape: &mut Shape| {
        if let Type::Text(text_content) = &mut shape.shape_type {
            text_content.set_grow_type(GrowType::from(grow_type));
        }
        // Don't throw error if the object is not text.
        // On swap component opperations is convenient.
    });
}

#[no_mangle]
pub extern "C" fn get_text_dimensions() -> *mut u8 {
    let mut ptr = std::ptr::null_mut();

    with_current_shape_mut!(state, |shape: &mut Shape| {
        if let Type::Text(content) = &mut shape.shape_type {
            let text_content_size = content.update_layout(shape.selrect);

            // Sacar de aqui x, y, width, height
            let rect = content.content_rect(&shape.selrect, shape.vertical_align);

            let mut bytes = vec![0; 20];
            bytes[0..4].clone_from_slice(&text_content_size.width.to_le_bytes());
            bytes[4..8].clone_from_slice(&text_content_size.height.to_le_bytes());
            bytes[8..12].clone_from_slice(&text_content_size.max_width.to_le_bytes());

            // veamos
            bytes[12..16].clone_from_slice(&rect.x().to_le_bytes());
            bytes[16..20].clone_from_slice(&rect.y().to_le_bytes());

            ptr = mem::write_bytes(bytes)
        }
    });

    // FIXME: I think it should be better if instead of returning
    // a NULL ptr we failed gracefully.
    ptr
}

#[no_mangle]
pub extern "C" fn intersect_position_in_shape(
    a: u32,
    b: u32,
    c: u32,
    d: u32,
    x_pos: f32,
    y_pos: f32,
) -> bool {
    with_state!(state, {
        let id = uuid_from_u32_quartet(a, b, c, d);
        let Some(shape) = state.shapes.get(&id) else {
            return false;
        };
        if let Type::Text(content) = &shape.shape_type {
            return content.intersect_position_in_text(shape, x_pos, y_pos);
        }
    });
    false
}

fn update_text_layout(shape: &mut Shape, force: bool) {
    if let Type::Text(text_content) = &mut shape.shape_type {
        if force {
            text_content.force_next_layout_update();
        }
        text_content.update_layout(shape.selrect);
        shape.invalidate_extrect();
    }
}

#[no_mangle]
pub extern "C" fn update_shape_text_layout() {
    with_current_shape_mut!(state, |shape: &mut Shape| {
        update_text_layout(shape, false);
    });
}

#[no_mangle]
pub extern "C" fn update_shape_text_layout_for(a: u32, b: u32, c: u32, d: u32) {
    with_state!(state, {
        let shape_id = uuid_from_u32_quartet(a, b, c, d);
        if let Some(shape) = state.shapes.get_mut(&shape_id) {
            update_text_layout(shape, false);
        }
        state.touch_shape(shape_id);
    });
}

#[no_mangle]
pub extern "C" fn force_update_shape_text_layout_for(a: u32, b: u32, c: u32, d: u32) {
    with_state!(state, {
        let shape_id = uuid_from_u32_quartet(a, b, c, d);
        if let Some(shape) = state.shapes.get_mut(&shape_id) {
            update_text_layout(shape, true);
        }
        state.touch_shape(shape_id);
    });
}

const RAW_POSITION_DATA_SIZE: usize = size_of::<shapes::PositionData>();

impl From<[u8; RAW_POSITION_DATA_SIZE]> for shapes::PositionData {
    fn from(bytes: [u8; RAW_POSITION_DATA_SIZE]) -> Self {
        unsafe { std::mem::transmute(bytes) }
    }
}

impl From<shapes::PositionData> for [u8; RAW_POSITION_DATA_SIZE] {
    fn from(value: shapes::PositionData) -> Self {
        unsafe { std::mem::transmute(value) }
    }
}

impl SerializableResult for shapes::PositionData {
    type BytesType = [u8; RAW_POSITION_DATA_SIZE];

    // The generic trait doesn't know the size of the array. This is why the
    // clone needs to be here even if it could be generic.
    fn clone_to_slice(&self, slice: &mut [u8]) {
        let bytes = Self::BytesType::from(*self);
        slice.clone_from_slice(&bytes);
    }
}

#[no_mangle]
pub extern "C" fn calculate_position_data() -> *mut u8 {
    let mut result = Vec::<shapes::PositionData>::default();
    with_current_shape!(state, |shape: &Shape| {
        if let Type::Text(text_content) = &shape.shape_type {
            result = shapes::calculate_position_data(shape, text_content, false);
        }
    });
    mem::write_vec(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The CLJS writer (texts.cljs) writes PARAGRAPH-ATTR-U8-SIZE (16)
    /// attr bytes after the u32 span count, and SPAN-ATTR-U8-SIZE (80)
    /// attr bytes before the fills block. These sizes must move in
    /// lockstep with the struct layouts.
    #[test]
    fn raw_struct_sizes_match_cljs_writer() {
        const PARAGRAPH_ATTR_U8_SIZE: usize = 16;
        const SPAN_ATTR_U8_SIZE: usize = 80;
        assert_eq!(RAW_PARAGRAPH_DATA_SIZE, 4 + PARAGRAPH_ATTR_U8_SIZE);
        assert_eq!(
            RAW_SPAN_DATA_SIZE,
            SPAN_ATTR_U8_SIZE + MAX_TEXT_FILLS * std::mem::size_of::<RawFillData>()
        );
    }

    #[test]
    fn raw_text_combine_upright_counts_deserialize() {
        // Byte 5 of the span attr block; the counted digits variants map
        // onto their max run length.
        let mut bytes = [0u8; RAW_SPAN_DATA_SIZE];
        bytes[5] = RawTextCombineUpright::Digits2 as u8;
        let span = shapes::TextSpan::from(RawTextSpan::from(bytes));
        assert_eq!(span.text_combine_upright.digits_max(), Some(2));

        bytes[5] = RawTextCombineUpright::Digits3 as u8;
        let span = shapes::TextSpan::from(RawTextSpan::from(bytes));
        assert_eq!(span.text_combine_upright.digits_max(), Some(3));

        bytes[5] = RawTextCombineUpright::Digits as u8;
        let span = shapes::TextSpan::from(RawTextSpan::from(bytes));
        assert_eq!(span.text_combine_upright.digits_max(), Some(4));
    }

    #[test]
    fn raw_font_features_deserializes_from_reserved_span_byte() {
        let mut bytes = [0u8; RAW_SPAN_DATA_SIZE];
        bytes[8] = RawFontFeatures::Vpal as u8;

        let raw = RawTextSpan::from(bytes);
        let span = shapes::TextSpan::from(raw);

        assert_eq!(span.font_features, FontFeatures::Vpal);
    }

    #[test]
    fn raw_annotation_clearance_deserializes_from_reserved_span_byte() {
        let mut bytes = [0u8; RAW_SPAN_DATA_SIZE];
        bytes[9] = RawAnnotationClearance::Auto as u8;

        let span = shapes::TextSpan::from(RawTextSpan::from(bytes));

        assert_eq!(span.annotation_clearance, AnnotationClearance::Auto);
    }

    #[test]
    fn raw_ruby_customization_deserializes_from_span_bytes() {
        let mut bytes = [0u8; RAW_SPAN_DATA_SIZE];
        bytes[10] = RawRubySize::Quarter as u8;
        bytes[11] = RawRubyAlign::SpaceBetween as u8;
        bytes[12] = RawRubyOverhang::None as u8;
        bytes[13] = RawRubySide::Under as u8;

        let span = shapes::TextSpan::from(RawTextSpan::from(bytes));

        assert_eq!(span.ruby_size, RubySize::Quarter);
        assert_eq!(span.ruby_align, RubyAlign::SpaceBetween);
        assert_eq!(span.ruby_overhang, RubyOverhang::None);
        assert_eq!(span.ruby_side, RubySide::Under);
    }
}
