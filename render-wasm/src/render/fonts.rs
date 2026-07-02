use skia_safe::{self as skia, textlayout, Font, FontMgr};
use std::collections::{HashMap, HashSet};

use crate::error::{Error, Result};
use crate::shapes::{FontFamily, FontStyle};
use crate::uuid::Uuid;

pub static DEFAULT_EMOJI_FONT: &str = "noto-color-emoji";

const DEFAULT_FONT_BYTES: &[u8] = include_bytes!("../fonts/sourcesanspro-regular.ttf");
const UI_FONT_BYTES: &[u8] = include_bytes!("../fonts/WorkSans-Numeric.ttf");

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
    ui_font: Font,
    fallback_fonts: HashSet<String>,
    /// Source URL registered when the font was fetched (SVG export references
    /// this in `@font-face` rules).
    source_urls: HashMap<String, String>,
}

impl FontStore {
    pub fn try_new() -> Result<Self> {
        let font_mgr = FontMgr::new();
        let font_provider = load_default_provider(&font_mgr);
        let mut font_collection = skia::textlayout::FontCollection::new();
        font_collection.set_default_font_manager(FontMgr::from(font_provider.clone()), None);

        let debug_typeface = font_provider
            .match_family_style(default_font().as_str(), skia::FontStyle::default())
            .ok_or(Error::CriticalError(
                "Failed to match default font".to_string(),
            ))?;

        let debug_font = skia::Font::new(debug_typeface, 12.0);

        let ui_typeface = font_mgr
            .new_from_data(UI_FONT_BYTES, None)
            .ok_or(Error::CriticalError("Failed to load UI font".to_string()))?;
        let ui_font = skia::Font::new(ui_typeface, 12.0);

        Ok(Self {
            font_mgr,
            font_provider,
            font_collection,
            debug_font,
            ui_font,
            fallback_fonts: HashSet::new(),
            source_urls: HashMap::new(),
        })
    }

    pub fn set_scale_debug_font(&mut self, dpr: f32) {
        let debug_font = skia::Font::new(self.debug_font.typeface(), 12.0 * dpr);
        self.debug_font = debug_font;
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

    pub fn ui_font(&self) -> &Font {
        &self.ui_font
    }

    pub fn add(
        &mut self,
        family: FontFamily,
        font_data: &[u8],
        is_emoji: bool,
        is_fallback: bool,
    ) -> Result<()> {
        if self.has_family(&family, is_emoji) {
            return Ok(());
        }

        let typeface = self
            .font_mgr
            .new_from_data(font_data, None)
            .ok_or(Error::CriticalError(
                "Failed to create typeface".to_string(),
            ))?;

        let alias = format!("{}", family);
        let font_name = if is_emoji {
            DEFAULT_EMOJI_FONT
        } else {
            alias.as_str()
        };

        self.font_provider.register_typeface(typeface, font_name);
        self.font_collection.clear_caches();

        if is_fallback {
            self.fallback_fonts.insert(alias);
        }

        Ok(())
    }

    pub fn has_family(&self, family: &FontFamily, is_emoji: bool) -> bool {
        let alias = format!("{}", family);
        let font_name = if is_emoji {
            DEFAULT_EMOJI_FONT
        } else {
            alias.as_str()
        };
        self.font_provider.family_names().any(|x| x == font_name)
    }

    pub fn get_fallback(&self) -> &HashSet<String> {
        &self.fallback_fonts
    }

    pub fn get_emoji_font(&self, _size: f32) -> Option<Font> {
        None
    }

    pub fn set_source_url(&mut self, alias: &str, url: String) {
        if !url.is_empty() {
            self.source_urls.insert(alias.to_string(), url);
        }
    }

    pub fn source_url(&self, alias: &str) -> Option<&str> {
        self.source_urls.get(alias).map(String::as_str)
    }

/// Builds `@font-face` CSS rules for the given registered aliases.
///
/// Each rule references the source URL registered for the alias at load time.
/// Aliases without a registered URL are omitted.
pub fn font_face_css_for_aliases(&self, aliases: &HashSet<String>) -> String {
        let mut seen: HashSet<String> = HashSet::new();
        let mut css = String::new();

        for alias in aliases {
            let Some(typeface) = self
                .font_provider
                .match_family_style(alias, skia::FontStyle::default())
            else {
                continue;
            };

            let family = typeface.family_name();
            let style = typeface.font_style();

            // Skia's SVG backend derives `<text>` font descriptors from the
            // typeface's own `SkFontStyle` using a quirky bucketed table (see
            // `skia_svg_font_weight`). We must mirror it exactly here so each
            // `@font-face` pairs with the `<text>` elements that reference it;
            // otherwise, when several weights of the same family coexist, the
            // browser cannot match the weight and silently falls back to 400.
            let weight = skia_svg_font_weight(*style.weight());
            let slant = match style.slant() {
                skia::font_style::Slant::Italic => "italic",
                skia::font_style::Slant::Oblique => "oblique",
                _ => "normal",
            };
            let stretch = skia_svg_font_stretch(*style.width());

            let dedup_key = format!("{family}|{weight}|{slant}|{stretch:?}");
            if !seen.insert(dedup_key) {
                continue;
            }

            let stretch_decl = stretch
                .map(|s| format!("font-stretch:{s};"))
                .unwrap_or_default();

            let Some(url) = self.source_url(alias) else {
                continue;
            };
            let src = font_face_src_from_url(url);

            css.push_str(&format!(
                "@font-face{{font-family:\"{family}\";font-style:{slant};font-weight:{weight};{stretch_decl}src:{src};}}",
            ));
        }

        css
    }
}

fn font_face_src_from_url(url: &str) -> String {
    let format = font_format_from_url(url);
    format!(
        "url(\"{}\") format(\"{format}\")",
        css_escape_url(url)
    )
}

fn font_format_from_url(url: &str) -> &'static str {
    let path = url
        .split('#')
        .next()
        .unwrap_or(url)
        .split('?')
        .next()
        .unwrap_or(url);
    if path.ends_with(".woff2") {
        "woff2"
    } else if path.ends_with(".woff") {
        "woff"
    } else if path.ends_with(".otf") {
        "opentype"
    } else {
        "truetype"
    }
}

fn css_escape_url(url: &str) -> String {
    url.replace('\\', "\\\\").replace('"', "\\\"")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shapes::{FontFamily, FontStyle};
    use crate::uuid::Uuid;

    #[test]
    fn font_face_css_uses_registered_url() {
        let mut store = FontStore::try_new().expect("font store");
        let family = FontFamily::new(Uuid::nil(), 400, FontStyle::Normal);
        let alias = family.alias();
        store.set_source_url(
            &alias,
            "https://example.com/fonts/source.ttf".to_string(),
        );

        let mut aliases = HashSet::new();
        aliases.insert(alias);
        let css = store.font_face_css_for_aliases(&aliases);

        assert!(css.contains("url(\"https://example.com/fonts/source.ttf\")"));
        assert!(css.contains("format(\"truetype\")"));
        assert!(!css.contains("base64,"));
    }

    #[test]
    fn font_face_css_skips_without_registered_url() {
        let store = FontStore::try_new().expect("font store");
        let family = FontFamily::new(Uuid::nil(), 400, FontStyle::Normal);
        let mut aliases = HashSet::new();
        aliases.insert(family.alias());
        let css = store.font_face_css_for_aliases(&aliases);

        assert!(css.is_empty());
    }
}

/// Reproduces the `font-weight` string that `SkSVGDevice::addTextAttributes`
/// writes on `<text>` elements for a given typeface weight.
fn skia_svg_font_weight(weight: i32) -> &'static str {
    // Skia's table is ["100","200","300","normal","400","500","600","bold",
    // "800","900"]; we substitute "400" for the omitted-normal bucket so the
    // descriptor still resolves to weight 400.
    const WEIGHTS: [&str; 10] = [
        "100", "200", "300", "400", "400", "500", "600", "bold", "800", "900",
    ];
    let index = ((weight.clamp(100, 900) - 50) / 100) as usize;
    WEIGHTS[index]
}

/// Reproduces the `font-stretch` value `SkSVGDevice` writes for a typeface
/// width, returning `None` for the normal width (which Skia omits).
fn skia_svg_font_stretch(width: i32) -> Option<&'static str> {
    const STRETCHES: [&str; 9] = [
        "ultra-condensed",
        "extra-condensed",
        "condensed",
        "semi-condensed",
        "normal",
        "semi-expanded",
        "expanded",
        "extra-expanded",
        "ultra-expanded",
    ];
    let index = width - 1;
    if index == 4 {
        return None;
    }
    STRETCHES.get(usize::try_from(index).ok()?).copied()
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
