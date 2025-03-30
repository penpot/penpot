use skia_safe::{self as skia, textlayout, Font, FontMgr};

use crate::shapes::FontFamily;

const DEFAULT_FONT_BYTES: &[u8] = include_bytes!("../fonts/RobotoMono-Regular.ttf");
const EMOJI_FONT_BYTES: &[u8] = include_bytes!("../fonts/NotoColorEmoji-Regular.ttf");
pub static DEFAULT_FONT: &'static str = "robotomono-regular";
pub static DEFAULT_EMOJI_FONT: &'static str = "noto-color-emoji";

pub struct FontStore {
    font_mgr: FontMgr,
    font_provider: textlayout::TypefaceFontProvider,
    font_collection: textlayout::FontCollection,
    debug_font: Font,
}

impl FontStore {
    pub fn new() -> Self {
        let font_mgr = FontMgr::new();

        let mut font_provider = skia::textlayout::TypefaceFontProvider::new();

        let default_font = font_mgr
            .new_from_data(DEFAULT_FONT_BYTES, None)
            .expect("Failed to load font");

        font_provider.register_typeface(default_font, DEFAULT_FONT);

        let emoji_font = font_mgr
            .new_from_data(EMOJI_FONT_BYTES, None)
            .expect("Failed to load font");

        font_provider.register_typeface(emoji_font, DEFAULT_EMOJI_FONT);

        let mut font_collection = skia::textlayout::FontCollection::new();
        font_collection.set_default_font_manager(FontMgr::from(font_provider.clone()), None);

        let debug_typeface = font_provider
            .match_family_style("robotomono-regular", skia::FontStyle::default())
            .unwrap();

        let debug_font = skia::Font::new(debug_typeface, 10.0);

        Self {
            font_mgr,
            font_provider,
            font_collection,
            debug_font,
        }
    }

    pub fn font_provider(&self) -> &textlayout::TypefaceFontProvider {
        &self.font_provider
    }

    pub fn font_collection(&self) -> &textlayout::FontCollection {
        &self.font_collection
    }

    pub fn debug_font(&self) -> &Font {
        &self.debug_font
    }

    pub fn add(&mut self, family: FontFamily, font_data: &[u8]) -> Result<(), String> {
        if self.has_family(&family) {
            return Ok(());
        }

        let alias = format!("{}", family);
        let typeface = self
            .font_mgr
            .new_from_data(font_data, None)
            .ok_or("Failed to create typeface")?;

        self.font_provider
            .register_typeface(typeface, alias.as_str());

        self.font_collection.clear_caches();

        Ok(())
    }

    pub fn has_family(&self, family: &FontFamily) -> bool {
        let serialized = format!("{}", family);
        self.font_provider.family_names().any(|x| x == serialized)
    }
}
