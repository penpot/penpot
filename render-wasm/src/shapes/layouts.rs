use macros::ToJs;

use crate::uuid::Uuid;

#[derive(Debug, Clone, PartialEq)]
#[allow(dead_code)]
pub enum Layout {
    FlexLayout(LayoutData, FlexData),
    GridLayout(LayoutData, GridData),
}

impl Layout {
    pub fn scale_content(&mut self, value: f32) {
        match self {
            Layout::FlexLayout(layout_data, _) => {
                layout_data.scale_content(value);
            }
            Layout::GridLayout(layout_data, grid_data) => {
                layout_data.scale_content(value);
                grid_data.scale_content(value);
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum FlexDirection {
    Row,
    RowReverse,
    Column,
    ColumnReverse,
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum GridDirection {
    Row,
    Column,
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum AlignItems {
    Start,
    End,
    Center,
    Stretch,
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum AlignContent {
    Start,
    End,
    Center,
    SpaceBetween,
    SpaceAround,
    SpaceEvenly,
    Stretch,
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum JustifyItems {
    Start,
    End,
    Center,
    Stretch,
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub enum JustifyContent {
    Start,
    End,
    Center,
    SpaceBetween,
    SpaceAround,
    SpaceEvenly,
    Stretch,
}

#[derive(Debug, Clone, PartialEq)]
pub enum WrapType {
    Wrap,
    NoWrap,
}

// TODO: maybe move this to the wasm module?
#[derive(Debug, Copy, Clone, PartialEq, ToJs)]
#[repr(u8)]
pub enum GridTrackType {
    Percent = 0,
    Flex = 1,
    Auto = 2,
    Fixed = 3,
}

// TODO: maybe move this to the wasm module?
impl GridTrackType {
    // TODO: implement a proper From trait for this
    // TODO: use transmute
    pub fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Percent,
            1 => Self::Flex,
            2 => Self::Auto,
            3 => Self::Fixed,
            _ => unreachable!(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct GridTrack {
    pub track_type: GridTrackType,
    pub value: f32,
}

impl GridTrack {
    pub fn from_raw(raw: &RawGridTrack) -> Self {
        Self {
            track_type: GridTrackType::from_u8(raw.track_type),
            value: f32::from_le_bytes(raw.value),
        }
    }

    pub fn scale_content(&mut self, value: f32) {
        if self.track_type == GridTrackType::Fixed {
            self.value *= value;
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct GridCell {
    pub row: i32,
    pub row_span: i32,
    pub column: i32,
    pub column_span: i32,
    pub align_self: Option<AlignSelf>,
    pub justify_self: Option<JustifySelf>,
    pub shape: Option<Uuid>,
}

// TODO: maybe move this to the wasm module?
#[derive(Debug, Clone, PartialEq, Copy, ToJs)]
#[repr(u8)]
pub enum Sizing {
    Fill = 0,
    Fix = 1,
    Auto = 2,
}

// TODO: maybe move this to the wasm module?
impl Sizing {
    // TODO: implement a proper From trait for this
    // TODO: use transmute
    pub fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Fill,
            1 => Self::Fix,
            2 => Self::Auto,
            _ => unreachable!(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct LayoutData {
    pub align_items: AlignItems,
    pub align_content: AlignContent,
    pub justify_items: JustifyItems,
    pub justify_content: JustifyContent,
    pub padding_top: f32,
    pub padding_right: f32,
    pub padding_bottom: f32,
    pub padding_left: f32,
    pub row_gap: f32,
    pub column_gap: f32,
}

impl LayoutData {
    pub fn scale_content(&mut self, value: f32) {
        self.padding_top *= value;
        self.padding_right *= value;
        self.padding_bottom *= value;
        self.padding_left *= value;
        self.row_gap *= value;
        self.column_gap *= value;
    }
}

#[derive(Debug, Copy, Clone, PartialEq)]
#[repr(u8)]
pub enum AlignSelf {
    Auto,
    Start,
    End,
    Center,
    Stretch,
}

#[derive(Debug, Copy, Clone, PartialEq)]
pub enum JustifySelf {
    Auto = 0,
    Start = 1,
    End = 2,
    Center = 3,
    Stretch = 4,
}

#[derive(Debug, Clone, PartialEq)]
pub struct FlexData {
    pub direction: FlexDirection,
    pub wrap_type: WrapType,
}

impl FlexData {
    pub fn is_reverse(&self) -> bool {
        matches!(
            &self.direction,
            FlexDirection::RowReverse | FlexDirection::ColumnReverse
        )
    }

    pub fn is_row(&self) -> bool {
        matches!(
            &self.direction,
            FlexDirection::RowReverse | FlexDirection::Row
        )
    }

    pub fn is_wrap(&self) -> bool {
        matches!(self.wrap_type, WrapType::Wrap)
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct GridData {
    pub direction: GridDirection,
    pub rows: Vec<GridTrack>,
    pub columns: Vec<GridTrack>,
    pub cells: Vec<GridCell>,
}

impl GridData {
    pub fn default() -> Self {
        Self {
            direction: GridDirection::Row,
            rows: vec![],
            columns: vec![],
            cells: vec![],
        }
    }

    pub fn scale_content(&mut self, value: f32) {
        self.rows.iter_mut().for_each(|t| t.scale_content(value));
        self.columns.iter_mut().for_each(|t| t.scale_content(value));
    }
}

// TODO: move this to the wasm module
// FIXME: use transmute
#[derive(Debug)]
#[repr(C)]
pub struct RawGridTrack {
    track_type: u8,
    value: [u8; 4],
}

impl RawGridTrack {
    pub fn from_bytes(bytes: [u8; 5]) -> Self {
        Self {
            track_type: bytes[0],
            value: [bytes[1], bytes[2], bytes[3], bytes[4]],
        }
    }
}

#[derive(Debug, Clone, PartialEq, Copy)]
pub struct LayoutItem {
    pub margin_top: f32,
    pub margin_right: f32,
    pub margin_bottom: f32,
    pub margin_left: f32,
    pub h_sizing: Sizing,
    pub v_sizing: Sizing,
    pub max_h: Option<f32>,
    pub min_h: Option<f32>,
    pub max_w: Option<f32>,
    pub min_w: Option<f32>,
    pub is_absolute: bool,
    pub z_index: i32,
    pub align_self: Option<AlignSelf>,
}

impl LayoutItem {
    pub fn scale_content(&mut self, value: f32) {
        self.margin_top *= value;
        self.margin_right *= value;
        self.margin_bottom *= value;
        self.margin_left *= value;
        self.max_h = self.max_h.map(|max_h| max_h * value);
        self.min_h = self.max_h.map(|max_h| max_h * value);
        self.max_w = self.max_h.map(|max_h| max_h * value);
        self.min_w = self.max_h.map(|max_h| max_h * value);
    }
}
