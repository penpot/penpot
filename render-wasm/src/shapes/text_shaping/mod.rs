//! Glyph shaping shared by the vertical engine and the horizontal annotation
//! painters (ruby, Zhuyin, emphasis): SkShaper runs with per-span font
//! fallback, upright/sideways orientation and the font tables (vmtx, vpal,
//! OS/2 typo metrics) the upright em box comes from.

mod font_tables;
mod orientation;
mod shaping;

pub(crate) use font_tables::*;
pub(crate) use orientation::*;
pub(crate) use shaping::*;
