use std::collections::HashSet;

use skia_safe::textlayout;

use super::fonts::FontStore;
use crate::utils::Browser;

/// Font and browser context for text shaping (paragraph builders, transforms).
pub struct TextShapingCtx<'a> {
    pub fonts: &'a FontStore,
    pub browser: Browser,
}

impl<'a> TextShapingCtx<'a> {
    pub fn new(fonts: &'a FontStore, browser: Browser) -> Self {
        Self { fonts, browser }
    }

    /// Live editor session: GPU `RenderState` fonts and the design browser.
    pub fn from_session() -> TextShapingCtx<'static> {
        TextShapingCtx {
            fonts: crate::get_render_state().fonts(),
            browser: Browser::from(crate::globals::get_design_state().current_browser),
        }
    }

    pub fn font_collection(&self) -> &textlayout::FontCollection {
        self.fonts.font_collection()
    }

    pub fn fallback_fonts(&self) -> &HashSet<String> {
        self.fonts.get_fallback()
    }
}
