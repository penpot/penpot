use skia_safe::{self as skia, textlayout, Font, FontMgr};

use crate::shapes::{FontFamily, FontStyle};
use crate::uuid::Uuid;

const EMOJI_FONT_BYTES: &[u8] = include_bytes!("../fonts/NotoColorEmoji-Regular.ttf");
pub static DEFAULT_EMOJI_FONT: &'static str = "noto-color-emoji";

const DEFAULT_FONT_BYTES: &[u8] = include_bytes!("../fonts/sourcesanspro-regular.ttf");

pub fn default_font() -> String {
    let family = FontFamily::new(default_font_uuid(), 400, FontStyle::Normal);
    format!("{}", family)
}

fn default_font_uuid() -> Uuid {
    Uuid::nil()
}

pub struct FontStore {
    font_mgr: FontMgr,
    font_provider: textlayout::TypefaceFontProvider,
    font_collection: textlayout::FontCollection,
    debug_font: Font,
}

impl FontStore {
    pub fn new() -> Self {
        let font_mgr = FontMgr::new();

        let mut font_provider = load_default_provider(&font_mgr);

        // TODO: Load emoji font lazily
        let emoji_font = font_mgr
            .new_from_data(EMOJI_FONT_BYTES, None)
            .expect("Failed to load font");
        font_provider.register_typeface(emoji_font, DEFAULT_EMOJI_FONT);

        let mut font_collection = skia::textlayout::FontCollection::new();
        font_collection.set_default_font_manager(FontMgr::from(font_provider.clone()), None);

        let debug_typeface = font_provider
            .match_family_style(default_font().as_str(), skia::FontStyle::default())
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

fn load_default_provider(font_mgr: &FontMgr) -> skia::textlayout::TypefaceFontProvider {
    let mut font_provider = skia::textlayout::TypefaceFontProvider::new();

    let family = FontFamily::new(default_font_uuid(), 400, FontStyle::Normal);
    let font = font_mgr
        .new_from_data(DEFAULT_FONT_BYTES, None)
        .expect("Failed to load font");
    font_provider.register_typeface(font, family.alias().as_str());

    font_provider
}
