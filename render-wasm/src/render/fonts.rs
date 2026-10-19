use skia_safe::{self as skia, textlayout, Font, FontMgr};
use std::collections::{BTreeSet, HashMap, HashSet};

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

/// CJK region whose glyph forms a fallback face draws, read from its family
/// name (`Noto Sans JP`, `Noto Sans TC`, ...). The declaration order is the
/// fallback order when no language prefers a face.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum CjkFace {
    SimplifiedChinese,
    TraditionalChinese,
    HongKongChinese,
    Japanese,
    Korean,
}

impl CjkFace {
    fn from_family_name(name: &str) -> Option<Self> {
        match name.rsplit(' ').next()? {
            "SC" => Some(Self::SimplifiedChinese),
            "TC" => Some(Self::TraditionalChinese),
            "HK" => Some(Self::HongKongChinese),
            "JP" => Some(Self::Japanese),
            "KR" => Some(Self::Korean),
            _ => None,
        }
    }

    /// Faces drawing Han and CJK punctuation in the forms `language` (a BCP 47
    /// tag) expects, best first. Without a CJK language, kana or hangul in
    /// `text` decide.
    fn preferred(language: &str, text: &str) -> &'static [Self] {
        let mut subtags = language.split(['-', '_']).map(str::to_ascii_lowercase);
        match subtags.next().as_deref() {
            Some("ja") => &[Self::Japanese],
            Some("ko") => &[Self::Korean],
            Some("zh") => {
                let subtags: Vec<String> = subtags.collect();
                let has = |tag: &str| subtags.iter().any(|subtag| subtag == tag);
                if has("hk") || has("mo") {
                    &[Self::HongKongChinese, Self::TraditionalChinese]
                } else if has("tw") || has("hant") {
                    &[Self::TraditionalChinese, Self::HongKongChinese]
                } else {
                    &[Self::SimplifiedChinese]
                }
            }
            _ if text.chars().any(is_kana) => &[Self::Japanese],
            _ if text.chars().any(is_hangul) => &[Self::Korean],
            _ => &[],
        }
    }
}

fn is_kana(c: char) -> bool {
    matches!(c, '\u{3040}'..='\u{30FF}' | '\u{31F0}'..='\u{31FF}' | '\u{FF66}'..='\u{FF9F}')
}

fn is_hangul(c: char) -> bool {
    matches!(c, '\u{1100}'..='\u{11FF}' | '\u{3130}'..='\u{318F}' | '\u{AC00}'..='\u{D7AF}')
}

/// Families that take part in character fallback, with the CJK region of
/// those that draw Han.
#[derive(Debug, Default)]
pub struct FallbackFonts {
    families: BTreeSet<String>,
    cjk_faces: HashMap<String, CjkFace>,
}

impl FallbackFonts {
    /// Fallback families without CJK regions, in name order.
    #[cfg(test)]
    pub fn from_families(families: impl IntoIterator<Item = String>) -> Self {
        Self {
            families: families.into_iter().collect(),
            cjk_faces: HashMap::new(),
        }
    }

    fn insert(&mut self, alias: String, cjk_face: Option<CjkFace>) {
        if let Some(cjk_face) = cjk_face {
            self.cjk_faces.insert(alias.clone(), cjk_face);
        }
        self.families.insert(alias);
    }

    #[cfg(test)]
    pub fn contains(&self, alias: &str) -> bool {
        self.families.contains(alias)
    }

    /// Fallback families for `text` in `language`: the CJK faces the language
    /// prefers, then the non-CJK faces, then the other CJK faces. CJK faces
    /// share Han characters with different glyph forms, so the first one
    /// decides how they look.
    pub fn for_text(&self, language: &str, text: &str) -> Vec<String> {
        let preferred = CjkFace::preferred(language, text);
        let mut families: Vec<&String> = self.families.iter().collect();
        families.sort_by_key(|alias| match self.cjk_faces.get(*alias) {
            Some(face) => match preferred.iter().position(|p| p == face) {
                Some(rank) => (0, rank),
                None => (2, *face as usize),
            },
            None => (1, 0),
        });
        families.into_iter().cloned().collect()
    }
}

pub struct FontStore {
    font_mgr: FontMgr,
    font_provider: textlayout::TypefaceFontProvider,
    font_collection: textlayout::FontCollection,
    debug_font: Font,
    ui_font: Font,
    fallback_fonts: FallbackFonts,
    /// CJK region of each registered family that draws Han.
    cjk_faces: HashMap<String, CjkFace>,
    /// Source URL registered when the font was fetched (SVG export references
    /// this in `@font-face` rules).
    source_urls: HashMap<String, String>,
}

impl FontStore {
    pub fn try_new() -> Result<Self> {
        // custom_empty: FreeType-backed manager with no system scan. FontMgr::new()
        // calls SkFontMgr_NewSystem, which on wasm tries to opendir font paths and
        // aborts when Emscripten is built with -sFILESYSTEM=0 (exporter + browser).
        let font_mgr = FontMgr::custom_empty().ok_or(Error::CriticalError(
            "Failed to create empty FontMgr".to_string(),
        ))?;
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
            .new_from_data(skia::Data::new_copy(UI_FONT_BYTES), None)
            .ok_or(Error::CriticalError("Failed to load UI font".to_string()))?;
        let ui_font = skia::Font::new(ui_typeface, 12.0);

        Ok(Self {
            font_mgr,
            font_provider,
            font_collection,
            debug_font,
            ui_font,
            fallback_fonts: FallbackFonts::default(),
            cjk_faces: HashMap::new(),
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
        let alias = format!("{}", family);
        if self.has_family(&family, is_emoji) {
            if is_fallback {
                self.insert_fallback(alias);
            }
            return Ok(());
        }

        let typeface = self
            .font_mgr
            .new_from_data(skia::Data::new_copy(font_data), None)
            .ok_or(Error::CriticalError(
                "Failed to create typeface".to_string(),
            ))?;

        let font_name = if is_emoji {
            DEFAULT_EMOJI_FONT
        } else {
            alias.as_str()
        };

        if let Some(cjk_face) = CjkFace::from_family_name(&typeface.family_name()) {
            self.cjk_faces.insert(alias.clone(), cjk_face);
        }
        self.font_provider.register_typeface(typeface, font_name);
        self.font_collection.clear_caches();

        if is_fallback {
            self.insert_fallback(alias);
        }

        Ok(())
    }

    fn insert_fallback(&mut self, alias: String) {
        let cjk_face = self.cjk_faces.get(&alias).copied();
        self.fallback_fonts.insert(alias, cjk_face);
    }

    /// Upgrade an already-uploaded family to take part in character fallback.
    /// Font bytes are cached apart from a face's role, so a family may arrive
    /// as a document font and later be requested as a fallback.
    pub fn mark_as_fallback(&mut self, family: &FontFamily) {
        if self.has_family(family, false) {
            self.insert_fallback(format!("{}", family));
        }
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

    pub fn get_fallback(&self) -> &FallbackFonts {
        &self.fallback_fonts
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
    /// Each rule references the source URL registered for the alias at load
    /// time. Aliases without a registered URL are omitted.
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
    format!("url(\"{}\") format(\"{format}\")", css_escape_url(url))
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
        .new_from_data(skia::Data::new_copy(DEFAULT_FONT_BYTES), None)
        .expect("Failed to load font");
    font_provider.register_typeface(font, family.alias().as_str());

    font_provider
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
        store.set_source_url(&alias, "https://example.com/fonts/source.ttf".to_string());

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

    const TEST_FONT: &[u8] = include_bytes!("../fonts/sourcesanspro-regular.ttf");

    fn cjk_fallbacks() -> FallbackFonts {
        let mut fallbacks = FallbackFonts::default();
        fallbacks.insert("arabic".to_string(), None);
        fallbacks.insert("jp".to_string(), Some(CjkFace::Japanese));
        fallbacks.insert("kr".to_string(), Some(CjkFace::Korean));
        fallbacks.insert("sc".to_string(), Some(CjkFace::SimplifiedChinese));
        fallbacks.insert("tc".to_string(), Some(CjkFace::TraditionalChinese));
        fallbacks.insert("hk".to_string(), Some(CjkFace::HongKongChinese));
        fallbacks
    }

    #[test]
    fn cjk_face_comes_from_the_family_region_suffix() {
        assert_eq!(
            CjkFace::from_family_name("Noto Sans JP"),
            Some(CjkFace::Japanese)
        );
        assert_eq!(
            CjkFace::from_family_name("Noto Sans HK"),
            Some(CjkFace::HongKongChinese)
        );
        assert_eq!(CjkFace::from_family_name("Noto Sans Arabic"), None);
        assert_eq!(CjkFace::from_family_name(""), None);
    }

    #[test]
    fn paragraph_language_puts_its_cjk_face_first() {
        let fallbacks = cjk_fallbacks();
        assert_eq!(fallbacks.for_text("ja", "東京")[0], "jp");
        assert_eq!(fallbacks.for_text("ko", "漢字")[0], "kr");
        assert_eq!(fallbacks.for_text("zh-Hans", "东京")[0], "sc");
        assert_eq!(fallbacks.for_text("zh-Hant-TW", "東京")[..2], ["tc", "hk"]);
        assert_eq!(fallbacks.for_text("zh-Hant-HK", "東京")[..2], ["hk", "tc"]);
        assert_eq!(fallbacks.for_text("zh_hant", "東京")[0], "tc");
    }

    #[test]
    fn untagged_text_prefers_the_face_of_its_kana_or_hangul() {
        let fallbacks = cjk_fallbacks();
        assert_eq!(fallbacks.for_text("", "東京へ行く")[0], "jp");
        assert_eq!(fallbacks.for_text("en", "서울")[0], "kr");
    }

    #[test]
    fn fallback_order_is_stable_without_a_preference() {
        let fallbacks = cjk_fallbacks();
        assert_eq!(
            fallbacks.for_text("", "東京"),
            ["arabic", "sc", "tc", "hk", "jp", "kr"]
        );
        // Non-CJK faces follow the preferred face, other CJK faces come last.
        assert_eq!(
            fallbacks.for_text("ja", "東京"),
            ["jp", "arabic", "sc", "tc", "hk", "kr"]
        );
    }

    #[test]
    fn uploaded_family_can_be_upgraded_to_fallback() {
        let mut store = FontStore::try_new().unwrap();
        let family = FontFamily::new(Uuid::nil(), 400, FontStyle::Normal);
        let alias = format!("{}", family);

        store.add(family, TEST_FONT, false, false).unwrap();
        assert!(!store.get_fallback().contains(&alias));

        store.mark_as_fallback(&family);
        assert!(store.get_fallback().contains(&alias));
    }
}
