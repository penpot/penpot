use super::justify::{horizontal_jlreq_justify_spacing, horizontal_squeeze_sheds};
use super::ruby::{
    horizontal_ruby_paint, horizontal_ruby_spacing_with, horizontal_ruby_targets, ruby_glyph_refs,
    split_counts_by_extent, RubyGlyphRef, RubyLine,
};
use super::*;
use crate::shapes::text_paragraph::LayoutParagraph;
use crate::shapes::text_policy::TextLayoutPolicy;
use crate::shapes::{LineAdjustment, TextAlign, TextDirection, TextTransform};
use crate::Uuid;

fn cjk_horizontal_layout(
    texts: &[&str],
    language: &str,
    wrap: crate::shapes::text_policy::KoreanWrap,
    width: f32,
) -> (Paragraph, HorizontalParagraphPlan, LayoutParagraph) {
    use crate::shapes::text_vertical::test_support::{provider, EM};
    init_state();
    let provider = provider(include_bytes!("../../fonts/cjk-locl-test.ttf"));
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_asset_font_manager(Some(provider.into()));
    let spans = texts
        .iter()
        .map(|text| {
            let mut span = make_span(text, 0.0);
            span.font_size = EM;
            span
        })
        .collect();
    let mut paragraph = make_paragraph(spans, 0.0);
    paragraph.set_language(language.to_owned());
    paragraph.set_korean_wrap(wrap);
    let (plan, layout) = cjk_layout_paragraph(&paragraph, width, &fonts);
    (paragraph, plan, layout)
}

fn cjk_layout_paragraph(
    paragraph: &Paragraph,
    width: f32,
    fonts: &skia::textlayout::FontCollection,
) -> (HorizontalParagraphPlan, LayoutParagraph) {
    let mut plan = HorizontalParagraphPlan::new(paragraph);
    let lay_out = |plan: &HorizontalParagraphPlan, width: f32| {
        let mut paragraph_style = paragraph.paragraph_to_style();
        paragraph_style.set_apply_rounding_hack(false);
        let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
        for (index, span) in paragraph.children().iter().enumerate() {
            let style = span.to_style(
                &crate::math::Rect::from_xywh(0.0, 0.0, width, 200.0),
                &crate::render::FallbackFonts::default(),
                false,
                1.0,
            );
            builder.push_style(&style);
            add_horizontal_span(
                &mut builder,
                span,
                &plan.texts[index],
                &plan.pair_spacing[index],
                &plan.ruby_spacing.adjustments[index],
                &style,
                fonts,
            );
            builder.pop();
        }
        let mut layout = LayoutParagraph::build(&mut builder);
        layout.layout(width);
        layout
    };
    plan.relax_overlong_words(paragraph, width, |plan| lay_out(plan, width));
    plan.retain_atomic_overflow(paragraph, width, lay_out);
    let layout = lay_out(&plan, plan.layout_width(width));
    (plan, layout)
}

#[test]
fn horizontal_chinese_regions_apply_compression_across_styles() {
    use crate::shapes::text_policy::KoreanWrap;
    let (_, cn, cn_layout) = cjk_horizontal_layout(
        &["文，", "。文"],
        "zh-Hans-CN",
        KoreanWrap::Syllable,
        1000.0,
    );
    let (_, tw, tw_layout) = cjk_horizontal_layout(
        &["文，", "。文"],
        "zh-Hant-TW",
        KoreanWrap::Syllable,
        1000.0,
    );
    assert!(!cn.pair_spacing[0].is_empty());
    assert!(tw.pair_spacing.iter().all(Vec::is_empty));
    assert!((tw_layout.longest_line() - cn_layout.longest_line() - 10.0).abs() < 0.1);
    assert_eq!(cn.texts.concat().replace('\u{2060}', ""), "文，。文");
}

#[test]
fn horizontal_korean_emergency_wrap_does_not_start_with_closing_punctuation() {
    use crate::shapes::text_policy::KoreanWrap;
    let (_, plan, layout) =
        cjk_horizontal_layout(&["한한", "한한", "。"], "ko", KoreanWrap::KeepWords, 42.0);
    let source: Vec<_> = "한한한한。".encode_utf16().collect();
    for line in layout.get_line_metrics() {
        let start = plan.offsets.offset_map.to_original(line.start_index);
        assert_ne!(source.get(start), Some(&(0x3002u16)));
    }
}

#[test]
fn word_mode_moves_a_fitting_word_whole_across_style_runs() {
    use crate::shapes::text_policy::KoreanWrap;
    let (_, plan, layout) =
        cjk_horizontal_layout(&["한글 ", "한", "국어"], "ko", KoreanWrap::KeepWords, 72.0);
    let starts: Vec<_> = layout
        .get_line_metrics()
        .iter()
        .map(|line| plan.offsets.builder_to_source(line.start_index))
        .collect();
    assert_eq!(starts, vec![0, 3]);
    assert!(
        !plan.texts.concat().contains('\u{200b}'),
        "a fitting word keeps all its joins"
    );
}

#[test]
fn emergency_word_boundaries_keep_identical_offsets_and_graphemes() {
    use crate::shapes::text_policy::KoreanWrap;
    let (paragraph, plan, layout) =
        cjk_horizontal_layout(&["ᄒ", "ᅡᆫ한。"], "ko", KoreanWrap::KeepWords, 42.0);
    let original = HorizontalParagraphPlan::new(&paragraph);
    assert!(plan.texts.concat().contains('\u{200b}'));
    for at in 0..=7 {
        assert_eq!(
            plan.offsets.source_to_builder(at),
            original.offsets.source_to_builder(at)
        );
    }
    for line in layout.get_line_metrics() {
        let at = plan.offsets.builder_to_source(line.start_index);
        assert!(
            at == 0 || at == 3,
            "no break inside a decomposed syllable or before closing punctuation: {at}"
        );
    }
    let (_, wide, _) = cjk_horizontal_layout(&["ᄒ", "ᅡᆫ한。"], "ko", KoreanWrap::KeepWords, 200.0);
    assert!(
        !wide.texts.concat().contains('\u{200b}'),
        "resizing restores whole-word joins"
    );
}

#[test]
fn cjk_emergency_wrapping_keeps_safe_boundaries_across_box_widths() {
    use crate::shapes::text_policy::KoreanWrap;
    use unicode_segmentation::UnicodeSegmentation;

    let cases: &[(&str, KoreanWrap, &[&str], &[f32])] = &[
        (
            "zh-Hans-CN",
            KoreanWrap::Syllable,
            &["文（", "中文）", "，中文"],
            &[65.0, 72.0, 85.0, 102.0, 140.0],
        ),
        (
            "zh-Hant-TW",
            KoreanWrap::Syllable,
            &["文（", "中文）", "，中文"],
            &[65.0, 72.0, 85.0, 102.0, 140.0],
        ),
        (
            "zh-Hant-HK",
            KoreanWrap::Syllable,
            &["文（", "中文）", "，中文"],
            &[65.0, 72.0, 85.0, 102.0, 140.0],
        ),
        (
            "ko",
            KoreanWrap::KeepWords,
            &["한글 ", "한국어", "。"],
            &[42.0, 52.0, 62.0, 72.0, 82.0, 100.0],
        ),
        (
            "ko",
            KoreanWrap::Syllable,
            &["한", "글（한", "글）와 1,234%"],
            &[65.0, 72.0, 85.0, 102.0, 140.0],
        ),
        (
            "ko",
            KoreanWrap::KeepWords,
            &["한 ", "ᄒ", "ᅡᆫ글。"],
            &[82.0, 100.0, 142.0],
        ),
    ];
    let mut violations = Vec::new();
    for &(language, wrap, texts, widths) in cases {
        let source = texts.concat();
        let chars: Vec<_> = source.chars().collect();
        let boundaries: Vec<_> = source
            .graphemes(true)
            .scan(0, |at, g| {
                let start = *at;
                *at += g.chars().count();
                Some(start)
            })
            .collect();
        for &width in widths {
            let (paragraph, plan, layout) = cjk_horizontal_layout(texts, language, wrap, width);
            for line in layout.get_line_metrics().into_iter().skip(1) {
                let at = plan.offsets.builder_to_source(line.start_index);
                if at < chars.len()
                    && (boundaries.binary_search(&at).is_err()
                        || (at > 0
                            && !paragraph
                                .layout_policy()
                                .emergency_break_allowed(chars[at - 1], chars[at])))
                {
                    violations.push(format!(
                        "{language} {wrap:?} {width}px: break at {at} in {source}"
                    ));
                }
            }
        }
    }
    assert!(violations.is_empty(), "{}", violations.join("\n"));
}

#[test]
fn narrower_than_atomic_unit_layout_keeps_source_offsets_and_finite_geometry() {
    use crate::shapes::text_policy::KoreanWrap;
    // Overflow preserves both the punctuation rule and editable positions.
    for language in ["zh-Hant-TW", "ko"] {
        for width in [1.0, 10.0, 19.0, 20.0, 30.0, 39.0, 39.999] {
            let (paragraph, plan, layout) =
                cjk_horizontal_layout(&["文", "。"], language, KoreanWrap::KeepWords, width);
            let original = HorizontalParagraphPlan::new(&paragraph);
            for at in 0..=2 {
                let builder = plan.offsets.source_to_builder(at);
                assert_eq!(plan.offsets.builder_to_source(builder), at);
                assert_eq!(builder, original.offsets.source_to_builder(at));
            }
            assert_eq!(layout.get_line_metrics().len(), 1);
            assert!(layout.height().is_finite() && layout.height() > 0.0);
            for line in layout.get_line_metrics() {
                assert!(line.width.is_finite() && line.width >= 0.0);
                assert!(line.baseline.is_finite());
            }
        }
    }
}

#[test]
fn horizontal_cjk_justification_is_bounded_and_word_mode_only_expands_spaces() {
    use crate::shapes::text_policy::KoreanWrap;
    let (paragraph, plan, layout) =
        cjk_horizontal_layout(&["文文", "文文"], "zh-Hant-TW", KoreanWrap::Syllable, 52.0);
    let extras =
        super::justify::horizontal_pairwise_justify_spacing(&paragraph, &layout, &plan, 52.0);
    assert!(extras[1].is_empty(), "last line stays unexpanded");
    let total: f32 = extras[0].iter().map(|(_, gap)| *gap).sum();
    assert!(total > 9.0 && total <= 10.0);
    let (paragraph, plan, layout) =
        cjk_horizontal_layout(&["한 한 한 한"], "ko", KoreanWrap::KeepWords, 72.0);
    let extras =
        super::justify::horizontal_pairwise_justify_spacing(&paragraph, &layout, &plan, 72.0);
    assert!(!extras[0].is_empty());
    for (at, amount) in &extras[0] {
        assert_eq!(plan.texts[0].chars().nth(*at), Some(' '));
        assert!(*amount <= 10.0);
    }
    let (paragraph, plan, layout) =
        cjk_horizontal_layout(&["한한한한"], "ko", KoreanWrap::KeepWords, 52.0);
    assert!(
        super::justify::horizontal_pairwise_justify_spacing(&paragraph, &layout, &plan, 52.0)
            .iter()
            .all(Vec::is_empty)
    );
}

#[test]
fn distribution_fills_the_last_line_between_hangul_graphemes_across_styles() {
    use crate::shapes::text_policy::KoreanWrap;
    let (paragraph, plan, layout) =
        cjk_horizontal_layout(&["기획", "편집"], "ko", KoreanWrap::KeepWords, 310.0);
    let mut paragraph = Paragraph::new(
        TextAlign::Distribute,
        TextDirection::LTR,
        None,
        None,
        1.0,
        0.0,
        paragraph.children().to_vec(),
    );
    paragraph.set_language("ko".to_owned());
    paragraph.set_korean_wrap(KoreanWrap::KeepWords);
    let extras =
        super::justify::horizontal_pairwise_justify_spacing(&paragraph, &layout, &plan, 310.0);
    let total: f32 = extras.iter().flatten().map(|(_, amount)| amount).sum();
    assert!((layout.longest_line() + total - 310.0).abs() < 0.1);
    let gaps: Vec<_> = extras.iter().flatten().map(|(_, amount)| *amount).collect();
    assert_eq!(gaps.len(), 3);
    assert!(gaps.windows(2).all(|p| (p[0] - p[1]).abs() < 0.001));
}

#[test]
fn distribution_keeps_graphemes_nonbreaking_pairs_and_ruby_bases_solid() {
    use crate::shapes::text_policy::KoreanWrap;
    init_state();
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_asset_font_manager(Some(
        crate::shapes::text_vertical::test_support::provider(include_bytes!(
            "../../fonts/cjk-locl-test.ttf"
        ))
        .into(),
    ));
    for (texts, expected_gaps) in [
        (vec!["ᄒ", "ᅡᆫ국"], 1),
        (vec!["한\u{2060}", "글"], 0),
        (vec!["한\u{00a0}글"], 0),
        (vec!["한"], 0),
        (vec![""], 0),
    ] {
        let spans = texts.iter().map(|text| make_span(text, 0.0)).collect();
        let mut paragraph = Paragraph::new(
            TextAlign::Distribute,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            spans,
        );
        paragraph.set_language("ko".to_owned());
        paragraph.set_korean_wrap(KoreanWrap::KeepWords);
        let (plan, layout) = cjk_layout_paragraph(&paragraph, 310.0, &fonts);
        let extras =
            super::justify::horizontal_pairwise_justify_spacing(&paragraph, &layout, &plan, 310.0);
        assert_eq!(
            extras.iter().map(Vec::len).sum::<usize>(),
            expected_gaps,
            "{texts:?}"
        );
    }
    let mut ruby = make_span("한글", 0.0);
    crate::globals::init_headless(1, 1);
    ruby.ruby = "hangeul".to_owned();
    let paragraph = Paragraph::new(
        TextAlign::Distribute,
        TextDirection::LTR,
        None,
        None,
        1.0,
        0.0,
        vec![ruby],
    );
    let (plan, layout) = cjk_layout_paragraph(&paragraph, 310.0, &fonts);
    assert!(
        super::justify::horizontal_pairwise_justify_spacing(&paragraph, &layout, &plan, 310.0)
            .iter()
            .all(Vec::is_empty)
    );
}

#[test]
fn chinese_proportional_punctuation_is_not_compressed_twice() {
    init_state();
    let mut span = make_span("，。", 0.0);
    span.font_features = FontFeatures::Palt;
    let mut paragraph = make_paragraph(vec![span], 0.0);
    paragraph.set_language("zh-Hans-CN".into());
    let plan = HorizontalParagraphPlan::new(&paragraph);
    assert!(plan.pair_spacing.iter().all(Vec::is_empty));
}

#[test]
fn untagged_cjk_keeps_plain_layout() {
    init_state();
    for text in ["中文，。", "日本 hello world", "한국 漢字"] {
        let paragraph = Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            vec![make_span(text, 0.0)],
        );
        let (texts, offsets) = paragraph.layout_span_texts();
        assert_eq!(texts, vec![text.to_string()]);
        assert!(offsets.is_empty());
        assert!(horizontal_pair_spacing(&paragraph, &texts)[0].is_empty());
    }
}

#[test]
fn explicit_profiles_use_distinct_rules_across_styles() {
    init_state();
    let mut paragraph = make_paragraph(
        vec![make_span("中A 文「", 0.0), make_span("字」っ", 0.0)],
        0.0,
    );
    paragraph.set_language("zh-Hans".to_string());
    let (texts, map) = paragraph.layout_span_texts();
    assert_eq!(texts.concat(), "中A 文「\u{2060}字\u{2060}」っ");
    for offset in 0..="中A 文「字」っ".encode_utf16().count() {
        assert_eq!(map.to_original(map.to_shifted(offset)), offset);
    }
    let plan = HorizontalParagraphPlan::new(&paragraph);
    assert!(plan.pair_spacing.iter().all(Vec::is_empty));

    let mut paragraph =
        make_paragraph(vec![make_span("한", 0.0), make_span("국어 문장", 0.0)], 0.0);
    paragraph.set_language("ko".to_string());
    assert_eq!(paragraph.layout_span_texts().0.concat(), "한국어 문장");
    paragraph.set_korean_wrap(crate::shapes::text_policy::KoreanWrap::SpacesOnly);
    assert_eq!(
        paragraph.layout_span_texts().0.concat(),
        "한\u{2060}국\u{2060}어 문\u{2060}장"
    );
}

#[test]
fn korean_joiners_stay_outside_decomposed_syllables_and_emoji() {
    init_state();
    let mut paragraph = make_paragraph(vec![make_span("ᄒ", 0.0), make_span("ᅡᆫ국 👩‍💻", 0.0)], 0.0);
    paragraph.set_language("ko".to_string());
    paragraph.set_korean_wrap(crate::shapes::text_policy::KoreanWrap::SpacesOnly);
    assert_eq!(paragraph.layout_span_texts().0.concat(), "한\u{2060}국 👩‍💻");
}

#[test]
fn same_paragraph_wraps_differently_by_profile_in_both_orientations() {
    use crate::shapes::text_vertical::test_support::{
        content_of, layout_with_height, provider, EM,
    };
    use crate::shapes::GrowType;

    init_state();
    let font_provider = provider(include_bytes!("../../fonts/cjk-locl-test.ttf"));
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_asset_font_manager(Some(font_provider.clone().into()));
    let mut horizontal_ends = Vec::new();
    let mut vertical_ends = Vec::new();
    for language in ["ja", "zh-Hans", "ko"] {
        let mut span = make_span("文文 あっ文文", 0.0);
        span.font_size = EM;
        let mut paragraph = make_paragraph(vec![span], 0.0);
        paragraph.set_language(language.to_string());
        paragraph.set_korean_wrap(crate::shapes::text_policy::KoreanWrap::SpacesOnly);
        let plan = HorizontalParagraphPlan::new(&paragraph);
        let content = content_of(vec![paragraph.clone()], EM * 5.45, GrowType::Fixed);
        let span = &paragraph.children()[0];
        let style = span.to_style(
            &content.bounds(),
            &crate::render::FallbackFonts::default(),
            false,
            1.0,
        );
        let mut builder = ParagraphBuilder::new(&paragraph.paragraph_to_style(), &fonts);
        builder.push_style(&style);
        add_horizontal_span(
            &mut builder,
            span,
            &plan.texts[0],
            &plan.pair_spacing[0],
            &[],
            &style,
            &fonts,
        );
        let mut horizontal = LayoutParagraph::build(&mut builder);
        horizontal.layout(EM * 5.45);
        let (_, offset_map) = paragraph.layout_span_texts();
        horizontal_ends.push(offset_map.to_original(horizontal.get_line_metrics()[0].end_index));
        let vertical = layout_with_height(&font_provider, &content, EM * 5.45);
        vertical_ends.push(
            vertical
                .cells
                .iter()
                .filter(|cell| cell.column == 0)
                .map(|cell| cell.end)
                .max()
                .unwrap(),
        );
    }
    assert_eq!(horizontal_ends, vec![6, 5, 3]);
    assert_eq!(vertical_ends, vec![6, 5, 3]);
}

#[test]
fn horizontal_ruby_ranges_use_utf16_across_spans() {
    init_state();
    let mut astral = make_span("𠀀", 0.0);
    astral.ruby = "あ".to_string();
    let mut kanji = make_span("漢", 0.0);
    kanji.ruby = "かん".to_string();
    let paragraph = make_paragraph(vec![astral, kanji], 0.0);
    let offsets = HorizontalOffsets::new(&paragraph);

    assert_eq!(
        horizontal_ruby_targets(&paragraph, &offsets),
        vec![(0, 0..2), (1, 2..3)]
    );
}

#[test]
fn split_counts_by_extent_drops_no_glyph() {
    assert_eq!(split_counts_by_extent(&[60.0, 40.0], 5), vec![3, 2]);
    assert_eq!(split_counts_by_extent(&[100.0], 4), vec![4]);
    assert_eq!(split_counts_by_extent(&[0.0, 0.0], 3), vec![0, 3]);
    assert_eq!(
        split_counts_by_extent(&[1.0, 1.0, 1.0], 2)
            .iter()
            .sum::<usize>(),
        2
    );
    assert!(split_counts_by_extent(&[], 3).is_empty());
}

// apply_text_transform reads the browser from the design state.
fn init_state() {
    crate::globals::design_init();
}

fn make_span(text: &str, letter_spacing: f32) -> TextSpan {
    TextSpan {
        text: text.to_string(),
        font_size: 16.0,
        line_height: 1.0,
        letter_spacing,
        ..TextSpan::default()
    }
}

fn make_paragraph(spans: Vec<TextSpan>, letter_spacing: f32) -> Paragraph {
    let mut paragraph = Paragraph::new(
        TextAlign::default(),
        TextDirection::LTR,
        None,
        None,
        1.0,
        letter_spacing,
        spans,
    );
    paragraph.set_language("ja".to_string());
    paragraph
}

#[test]
fn proportional_line_spacing_preserves_fractional_baselines() {
    use crate::shapes::text_vertical::test_support::provider;
    init_state();
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_asset_font_manager(Some(
        provider(include_bytes!("../../fonts/cjk-locl-test.ttf")).into(),
    ));
    for size in [18.0, 36.0] {
        let mut span = make_span("한글\n한글\n한글", 0.0);
        span.font_size = size;
        let paragraph = Paragraph::new(
            TextAlign::Left,
            TextDirection::LTR,
            None,
            None,
            1.6,
            0.0,
            vec![span],
        );
        let mut builder = ParagraphBuilder::new(&paragraph.paragraph_to_style(), &fonts);
        let style = paragraph.children()[0].to_style(
            &crate::math::Rect::from_xywh(0.0, 0.0, 300.0, 300.0),
            &crate::render::FallbackFonts::default(),
            false,
            paragraph.line_height(),
        );
        builder.push_style(&style);
        builder.add_text(&paragraph.children()[0].text);
        let mut layout = LayoutParagraph::build(&mut builder);
        layout.layout(300.0);
        let lines = layout.get_line_metrics();
        assert_eq!(lines.len(), 3);
        for pair in lines.windows(2) {
            let step = (pair[1].baseline - pair[0].baseline) as f32;
            assert!(
                (step - size * 1.6).abs() < 0.001,
                "{size}px: {step}, lines: {lines:?}"
            );
        }
    }
}

#[test]
fn layout_span_texts_applies_kinsoku() {
    init_state();
    let paragraph = make_paragraph(vec![make_span("雪国", 0.0), make_span("。です", 0.0)], 0.0);
    let (texts, map) = paragraph.layout_span_texts();
    assert_eq!(
        texts,
        vec!["雪国".to_string(), "\u{2060}。です".to_string()]
    );
    assert!(!map.is_empty());
    assert_eq!(map.to_original(3), 2);
}

#[test]
fn layout_span_texts_applies_kinsoku_under_paragraph_letter_spacing() {
    init_state();
    let paragraph = make_paragraph(vec![make_span("雪国。", 0.0)], 2.0);
    let (texts, _) = paragraph.layout_span_texts();
    assert_eq!(texts, vec!["雪国\u{2060}。".to_string()]);
}

#[test]
fn layout_span_texts_applies_kinsoku_under_span_letter_spacing() {
    init_state();
    let paragraph = make_paragraph(vec![make_span("雪国。", 1.5)], 0.0);
    let (texts, _) = paragraph.layout_span_texts();
    assert_eq!(texts, vec!["雪国\u{2060}。".to_string()]);
}

#[test]
fn inserted_joiners_take_no_letter_spacing() {
    init_state();
    let measure = |text: &str| {
        let span = make_span(text, 5.0);
        let mut style = skia::textlayout::TextStyle::default();
        style.set_font_size(span.font_size);
        style.set_letter_spacing(span.letter_spacing);
        let mut fonts = skia::textlayout::FontCollection::new();
        fonts.set_default_font_manager(skia::FontMgr::new(), None);
        let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), &fonts);
        builder.push_style(&style);
        add_horizontal_span(&mut builder, &span, text, &[], &[], &style, &fonts);
        let mut laid_out = LayoutParagraph::build(&mut builder);
        laid_out.layout(f32::MAX);
        laid_out.longest_line()
    };
    assert!((measure("A\u{2060}B") - measure("AB")).abs() < 0.01);
}

#[test]
fn layout_span_texts_respects_text_transform() {
    init_state();
    let mut span = make_span("hello。", 0.0);
    span.text_transform = Some(TextTransform::Uppercase);
    let paragraph = make_paragraph(vec![span], 0.0);
    let (texts, _) = paragraph.layout_span_texts();
    assert_eq!(texts, vec!["HELLO\u{2060}。".to_string()]);
}

#[test]
fn layout_span_texts_identity_map_for_plain_text() {
    init_state();
    let paragraph = make_paragraph(vec![make_span("helloworld", 0.0)], 0.0);
    let (texts, map) = paragraph.layout_span_texts();
    assert_eq!(texts, vec!["helloworld".to_string()]);
    assert!(map.is_empty());
    assert_eq!(map.to_original(5), 5);
    assert_eq!(map.to_shifted(5), 5);
}

#[test]
fn layout_span_texts_leaves_text_without_japanese_unchanged() {
    init_state();
    let paragraph = make_paragraph(vec![make_span("hello world foo", 0.0)], 0.0);
    let (texts, map) = paragraph.layout_span_texts();
    assert_eq!(texts, vec!["hello world foo".to_string()]);
    assert!(map.is_empty());
}

#[test]
fn layout_span_texts_sets_western_spaces_inside_japanese_text() {
    init_state();
    let paragraph = make_paragraph(vec![make_span("日本 hello world", 0.0)], 0.0);
    let (texts, _) = paragraph.layout_span_texts();
    assert_eq!(
        texts,
        vec![format!(
            "日本{}hello{}world",
            line_breaks::WESTERN_WORD_SPACE,
            line_breaks::WESTERN_WORD_SPACE
        )]
    );
}

#[test]
fn layout_span_texts_keeps_ruby_atomic_without_japanese_text() {
    init_state();
    let mut group = make_span("ab", 0.0);
    group.ruby = "x".to_string();
    let paragraph = make_paragraph(vec![group], 0.0);
    assert_eq!(
        paragraph.layout_span_texts().0,
        vec!["a\u{2060}b".to_string()]
    );
}

fn test_ruby_spacing(paragraph: &Paragraph) -> HorizontalRubySpacing {
    let (texts, offset_map) = paragraph.layout_span_texts();
    let provider = skia::textlayout::TypefaceFontProvider::new();
    let typeface = FontMgr::new()
        .new_from_data(
            skia_safe::Data::new_copy(include_bytes!("../../fonts/sourcesanspro-regular.ttf")),
            None,
        )
        .expect("test font");
    let mut provider = provider;
    provider.register_typeface(typeface, Some("sourcesanspro"));
    horizontal_ruby_spacing_with(
        paragraph,
        &texts,
        &offset_map,
        &provider,
        &crate::render::FallbackFonts::from_families(["sourcesanspro".to_string()]),
    )
}

#[test]
fn horizontal_ruby_overhang_room_covers_kana_neighbours_only() {
    init_state();
    let mut base = make_span("字", 0.0);
    base.ruby = "じじじ".to_string();
    let paragraph = make_paragraph(vec![make_span("か", 0.0), base, make_span("漢", 0.0)], 0.0);
    let spacing = test_ruby_spacing(&paragraph);
    assert_eq!(spacing.rooms[1], (8.0, 0.0));
}

#[test]
fn horizontal_long_ruby_spreads_its_base_without_overhang() {
    init_state();
    let mut base = make_span("i", 0.0);
    base.ruby = "MMMMMMMM".to_string();
    base.ruby_overhang = RubyOverhang::None;
    let paragraph = make_paragraph(vec![make_span("a", 0.0), base, make_span("b", 0.0)], 0.0);
    let spacing = test_ruby_spacing(&paragraph);
    let gap = spacing.base_gaps[1];
    assert!(gap > 0.0, "the base grows under a long reading");
    assert_eq!(spacing.adjustments[1], vec![(0, gap / 2.0)]);
    assert_eq!(spacing.adjustments[0], vec![(0, gap / 2.0)]);
    assert_eq!(spacing.leading_gaps[1], gap / 2.0);
}

#[test]
fn horizontal_ruby_base_at_paragraph_start_takes_all_spacing_after() {
    init_state();
    let mut base = make_span("i", 0.0);
    base.ruby = "MMMMMMMM".to_string();
    base.ruby_overhang = RubyOverhang::None;
    let paragraph = make_paragraph(vec![base, make_span("b", 0.0)], 0.0);
    let spacing = test_ruby_spacing(&paragraph);
    let gap = spacing.base_gaps[0];
    assert!(gap > 0.0);
    assert_eq!(
        spacing.leading_gaps[0], 0.0,
        "nothing sticks out before the line start"
    );
    assert_eq!(spacing.adjustments[0], vec![(0, gap / 2.0), (0, gap / 2.0)]);
}

#[test]
fn horizontal_long_ruby_counts_only_source_characters_of_its_base() {
    init_state();
    let mut base = make_span("第3", 0.0);
    base.ruby = "MMMMMMMMMMMM".to_string();
    base.ruby_overhang = RubyOverhang::None;
    let paragraph = make_paragraph(vec![base], 0.0);
    let (texts, _) = paragraph.layout_span_texts();
    assert!(
        texts[0].chars().count() > 2,
        "the layout text has inserted characters"
    );
    let last = texts[0].chars().count() - 1;
    let spacing = test_ruby_spacing(&paragraph);
    let gap = spacing.base_gaps[0];
    assert_eq!(
        spacing.adjustments[0],
        vec![(0, gap), (last, gap / 2.0), (last, gap / 2.0)]
    );
}

#[test]
fn horizontal_short_ruby_adds_no_spacing() {
    init_state();
    let mut base = make_span("MMMM", 0.0);
    base.ruby = "i".to_string();
    let paragraph = make_paragraph(vec![base], 0.0);
    let spacing = test_ruby_spacing(&paragraph);
    assert_eq!(spacing.base_gaps, vec![0.0]);
    assert!(spacing.adjustments[0].is_empty());
}

#[test]
fn annotation_room_follows_ruby_size_and_clearance() {
    let mut span = make_span("漢字", 0.0);
    span.ruby = "かんじ".to_string();
    assert_eq!(span.annotation_room_em(), 0.0, "none keeps the line height");
    span.annotation_clearance = AnnotationClearance::Auto;
    span.ruby_size = RubySize::Quarter;
    assert_eq!(span.annotation_room_em(), 0.25);
    span.text_emphasis = TextEmphasis::FilledDot;
    assert_eq!(span.annotation_room_em(), 0.75);
}

#[test]
fn horizontal_plans_are_cached_until_content_or_width_changes() {
    init_state();
    let mut content = super::super::text::TextContent::new(
        crate::math::Rect::from_xywh(0.0, 0.0, 200.0, 40.0),
        crate::shapes::GrowType::Fixed,
    );
    content.add_paragraph(make_paragraph(vec![make_span("雪国。", 0.0)], 0.0));
    let first = content.horizontal_plans();

    assert!(std::rc::Rc::ptr_eq(&first, &content.horizontal_plans()));
    assert!(
        std::rc::Rc::ptr_eq(&first, &content.clone().horizontal_plans()),
        "clones share the cached plans"
    );
    assert_eq!(first[0].texts, vec!["雪国\u{2060}。".to_string()]);

    content.paragraphs_mut()[0].children_mut()[0].text = "です".to_string();
    let edited = content.horizontal_plans();
    assert!(!std::rc::Rc::ptr_eq(&first, &edited));
    assert_eq!(edited[0].texts, vec!["です".to_string()]);

    content.set_xywh(10.0, 20.0, 200.0, 80.0);
    assert!(std::rc::Rc::ptr_eq(&edited, &content.horizontal_plans()));
    content.set_xywh(10.0, 20.0, 100.0, 80.0);
    let resized = content.horizontal_plans();
    assert!(!std::rc::Rc::ptr_eq(&edited, &resized));
    assert!(std::rc::Rc::ptr_eq(&resized, &content.horizontal_plans()));
}

#[test]
fn horizontal_annotations_can_paint_from_the_layout_cache() {
    let content_with = |span: TextSpan| {
        let mut content = super::super::text::TextContent::new(
            crate::math::Rect::from_xywh(0.0, 0.0, 200.0, 40.0),
            crate::shapes::GrowType::Fixed,
        );
        content.add_paragraph(make_paragraph(vec![span], 0.0));
        content
    };
    let mut ruby = make_span("漢字", 0.0);
    ruby.ruby = "かんじ".to_string();
    let mut emphasis = make_span("強調", 0.0);
    emphasis.text_emphasis = TextEmphasis::FilledDot;
    let mut warichu = make_span("割注入り", 0.0);
    warichu.warichu = true;
    for span in [ruby, emphasis, warichu] {
        assert!(content_with(span).can_paint_from_layout_cache());
    }

    let mut vertical = content_with(make_span("縦", 0.0));
    vertical.paragraphs_mut()[0].set_writing_mode(WritingMode::VerticalRl);
    assert!(!vertical.can_paint_from_layout_cache());
}

#[test]
fn emoji_detection_ignores_japanese_text() {
    let content_with = |text: &str| {
        let mut content = super::super::text::TextContent::new(
            crate::math::Rect::from_xywh(0.0, 0.0, 200.0, 40.0),
            crate::shapes::GrowType::Fixed,
        );
        content.add_paragraph(make_paragraph(vec![make_span(text, 0.0)], 0.0));
        content
    };
    assert!(!content_with("日本語のテキスト、「括弧」").has_emoji());
    assert!(content_with("あ😀").has_emoji());
    assert!(content_with("#\u{FE0F}\u{20E3}").has_emoji());
    assert!(content_with("©\u{FE0F}").has_emoji());
}

#[test]
fn horizontal_ruby_is_atomic() {
    init_state();
    let mut group = make_span("日本", 0.0);
    group.ruby = "にほん".to_string();
    let paragraph = make_paragraph(vec![group], 0.0);
    assert_eq!(
        paragraph.layout_span_texts().0,
        vec!["日\u{2060}本".to_string()]
    );
}

#[test]
fn horizontal_warichu_collapses_to_one_builder_position() {
    init_state();
    let mut warichu = make_span("割注入り", 0.0);
    warichu.warichu = true;
    let paragraph = make_paragraph(vec![warichu, make_span("後", 0.0)], 0.0);

    let ranges = horizontal_span_ranges(&paragraph);
    assert_eq!(ranges[0].builder_start..ranges[0].builder_end, 0..3);
    assert_eq!(ranges[1].builder_start..ranges[1].builder_end, 3..4);
    assert_eq!(HorizontalOffsets::new(&paragraph).source_to_builder(2), 0);
    assert_eq!(HorizontalOffsets::new(&paragraph).source_to_builder(4), 3);
    assert_eq!(HorizontalOffsets::new(&paragraph).source_to_builder(5), 4);
    assert_eq!(horizontal_builder_to_source(&paragraph, 1), 4);
    assert_eq!(horizontal_builder_to_source(&paragraph, 2), 4);
    assert_eq!(horizontal_builder_to_source(&paragraph, 3), 4);
    assert_eq!(horizontal_builder_to_source(&paragraph, 4), 5);
    assert_eq!(
        HorizontalOffsets::new(&paragraph).normal_selection_ranges(&paragraph, 1, 5),
        vec![3..4]
    );
}

#[test]
fn horizontal_builder_mapping_preserves_non_bmp_boundaries() {
    init_state();
    let paragraph = make_paragraph(vec![make_span("😀A", 0.0)], 0.0);

    assert_eq!(HorizontalOffsets::new(&paragraph).source_to_builder(1), 2);
    assert_eq!(horizontal_builder_to_source(&paragraph, 2), 1);
    assert_eq!(HorizontalOffsets::new(&paragraph).source_to_builder(2), 3);
    assert_eq!(horizontal_builder_to_source(&paragraph, 3), 2);
}

#[test]
fn horizontal_warichu_builder_emits_one_styled_placeholder() {
    init_state();
    let mut span = make_span("割注入り", 0.0);
    span.warichu = true;
    let mut style = skia::textlayout::TextStyle::default();
    style.set_font_size(span.font_size);
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_default_font_manager(skia::FontMgr::new(), None);
    let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), &fonts);
    builder.push_style(&style);
    add_horizontal_span(&mut builder, &span, &span.text, &[], &[], &style, &fonts);
    let mut laid_out = LayoutParagraph::build(&mut builder);
    laid_out.layout(200.0);

    let placeholders = laid_out.get_rects_for_placeholders();
    assert_eq!(placeholders.len(), 1);
    assert!(placeholders[0].rect.width() > 0.0);
    assert!(placeholders[0].rect.height() > 0.0);
    let has_style = laid_out
        .get_line_metrics()
        .iter()
        .any(|line| !line.get_style_metrics(3..5).is_empty());
    assert!(
        has_style,
        "the paint pass must recover the placeholder style"
    );
}

#[test]
fn horizontal_warichu_allows_wrapping_after_the_atomic_box() {
    init_state();
    let mut span = make_span("割注入り", 0.0);
    span.warichu = true;
    let following = make_span("A", 0.0);
    let mut style = skia::textlayout::TextStyle::default();
    style.set_font_size(span.font_size);
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_default_font_manager(skia::FontMgr::new(), None);
    let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), &fonts);
    builder.push_style(&style);
    add_horizontal_span(&mut builder, &span, &span.text, &[], &[], &style, &fonts);
    builder.push_style(&style);
    builder.add_text(&following.text);

    let mut laid_out = LayoutParagraph::build(&mut builder);
    laid_out.layout(16.1);

    assert_eq!(laid_out.get_rects_for_placeholders().len(), 1);
    assert_eq!(laid_out.get_line_metrics().len(), 2);
}

#[test]
fn warichu_sub_lines_paint_on_one_line() {
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_default_font_manager(skia::FontMgr::new(), None);
    for (text, size, letter_spacing) in [
        ("書き下ろし", 13.0, 0.75),
        ("浅煎り・", 13.0, 0.0),
        ("税込・", 53.0, -1.5),
        ("架空の", 7.5, 0.0),
    ] {
        let mut style = skia::textlayout::TextStyle::default();
        style.set_font_size(size);
        style.set_height(1.0);
        style.set_height_override(true);
        style.set_letter_spacing(letter_spacing);
        let paragraph = mini_paragraph(text, &style, &fonts);
        assert_eq!(paragraph.line_number(), 1, "{text:?} must not wrap");
    }
}

#[test]
fn horizontal_pair_spacing_follow_punctuation_pairs() {
    init_state();
    let texts = vec!["あ。」い、".to_string(), "「「う".to_string()];
    let paragraph = make_paragraph(texts.iter().map(|text| make_span(text, 0.0)).collect(), 0.0);
    assert_eq!(
        horizontal_pair_spacing(&paragraph, &texts),
        vec![vec![(1, 8.0), (4, 8.0)], vec![(0, 8.0)]],
        "。 before 」, 、 before 「 across spans, and 「 before 「"
    );
}

#[test]
fn leading_aki_shed_uses_the_bracket_font_size() {
    init_state();
    let texts = vec!["「".to_string(), "「う".to_string()];
    let mut large = make_span("「う", 0.0);
    large.font_size = 32.0;
    let paragraph = make_paragraph(vec![make_span("「", 0.0), large], 0.0);
    assert_eq!(
        horizontal_pair_spacing(&paragraph, &texts),
        vec![vec![(0, 16.0)], vec![]],
        "the 16px 「 gives up half of the next 32px bracket's em"
    );
}

#[test]
fn horizontal_pair_spacing_skip_curly_quotes() {
    init_state();
    for text in ["“‘Hi’”", "あ“い”。", "あ‘い’、"] {
        let texts = vec![text.to_string()];
        let paragraph = make_paragraph(vec![make_span(text, 0.0)], 0.0);
        assert_eq!(
            horizontal_pair_spacing(&paragraph, &texts),
            vec![Vec::<(usize, f32)>::new()],
            "no aki shed in {text:?}"
        );
    }
}

#[test]
fn horizontal_pair_spacing_skip_word_joiners_and_palt() {
    init_state();
    let joined = vec!["。\u{2060}」".to_string()];
    let paragraph = make_paragraph(vec![make_span(&joined[0], 0.0)], 0.0);
    assert_eq!(
        horizontal_pair_spacing(&paragraph, &joined),
        vec![vec![(0, 8.0)]]
    );

    let mut palt = make_span("。」", 0.0);
    palt.font_features = FontFeatures::Palt;
    let paragraph = make_paragraph(vec![palt], 0.0);
    assert_eq!(
        horizontal_pair_spacing(&paragraph, &["。」".to_string()]),
        vec![Vec::<(usize, f32)>::new()]
    );
}

#[test]
fn horizontal_shed_sets_the_closing_mark_half_an_em_narrower() {
    init_state();
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
    let width = |span: TextSpan| {
        let mut content = super::super::text::TextContent::new(
            crate::math::Rect::from_xywh(0.0, 0.0, 400.0, 100.0),
            crate::shapes::GrowType::Fixed,
        );
        content.add_paragraph(make_paragraph(vec![span], 0.0));
        let mut groups = content.paragraph_builder_group_from_text(None);
        let mut paragraph = groups[0][0].build();
        paragraph.layout(1000.0);
        paragraph.max_intrinsic_width()
    };
    let mut palt = make_span("。」", 0.0);
    palt.font_features = FontFeatures::Palt;
    let solid = width(make_span("。」", 0.0));
    let spaced = width(palt);
    assert!(
        (spaced - solid - 8.0).abs() < 0.01,
        "the period sheds half of its 16px em: {spaced} vs {solid}"
    );
}

#[test]
fn horizontal_ruby_targets_use_builder_offsets() {
    init_state();
    let mut note = make_span("割注入り", 0.0);
    note.warichu = true;
    let mut base = make_span("漢字", 0.0);
    base.ruby = "かんじ".to_string();
    let paragraph = make_paragraph(vec![note, base], 0.0);
    let offsets = HorizontalOffsets::new(&paragraph);
    assert_eq!(
        horizontal_ruby_targets(&paragraph, &offsets),
        vec![(1, 3..6)],
        "the warichu span takes three builder units; 漢\u{2060}字 carries a joiner"
    );
}

#[test]
fn horizontal_ruby_paint_follows_the_pass_paint() {
    init_state();
    let mut base = make_span("漢字", 0.0);
    base.ruby = "かんじ".to_string();
    let paragraph = make_paragraph(vec![base.clone()], 0.0);
    let mut stroke = skia::Paint::default();
    stroke.set_style(skia::PaintStyle::Stroke);
    stroke.set_color(skia::Color::RED);
    let mut style = skia::textlayout::TextStyle::default();
    style.set_font_size(base.font_size);
    style.set_foreground_paint(&stroke);
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_default_font_manager(skia::FontMgr::new(), None);
    let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), &fonts);
    builder.push_style(&style);
    add_horizontal_span(&mut builder, &base, &base.text, &[], &[], &style, &fonts);
    let mut laid_out = LayoutParagraph::build(&mut builder);
    laid_out.layout(400.0);

    let offsets = HorizontalOffsets::new(&paragraph);
    let paint = horizontal_ruby_paint(&laid_out, &offsets.ranges[0], skia::Paint::default());
    assert_eq!(paint.style(), skia::PaintStyle::Stroke);
    assert_eq!(paint.color(), skia::Color::RED);
}

#[test]
fn horizontal_position_data_skips_tab_placeholders_before_warichu() {
    init_state();
    let mut warichu = make_span("割注入り", 0.0);
    warichu.warichu = true;
    let mut content = super::super::text::TextContent::new(
        crate::math::Rect::from_xywh(0.0, 0.0, 400.0, 100.0),
        crate::shapes::GrowType::Fixed,
    );
    content.add_paragraph(make_paragraph(vec![make_span("A\tB", 0.0), warichu], 0.0));
    let mut shape = crate::shapes::Shape::new(Uuid::nil());
    shape.set_selrect(0.0, 0.0, 400.0, 100.0);
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);

    let data = super::super::text::calculate_position_data(&shape, &content, false);

    let text_right = data
        .iter()
        .filter(|entry| entry.span == 0)
        .map(|entry| entry.x + entry.width)
        .fold(0.0f32, f32::max);
    let warichu_left = data
        .iter()
        .filter(|entry| entry.span == 1)
        .map(|entry| entry.x)
        .fold(f32::MAX, f32::min);
    assert!(
        warichu_left >= text_right - 0.5,
        "the warichu strips start at {warichu_left}, inside A\tB which ends at {text_right}"
    );
}

#[test]
fn horizontal_position_data_carries_warichu_lines_and_emphasis_marks() {
    init_state();
    let mut emphasized = make_span("A、B", 0.0);
    emphasized.text_emphasis = TextEmphasis::FilledDot;
    let mut warichu = make_span("割注入り", 0.0);
    warichu.warichu = true;
    let mut content = super::super::text::TextContent::new(
        crate::math::Rect::from_xywh(0.0, 0.0, 400.0, 100.0),
        crate::shapes::GrowType::Fixed,
    );
    content.add_paragraph(make_paragraph(vec![emphasized, warichu], 0.0));
    let mut shape = crate::shapes::Shape::new(Uuid::nil());
    shape.set_selrect(0.0, 0.0, 400.0, 100.0);
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);

    let data = super::super::text::calculate_position_data(&shape, &content, false);

    let warichu_lines: Vec<(u32, u32)> = data
        .iter()
        .filter(|entry| entry.span == 1)
        .map(|entry| (entry.start_pos, entry.end_pos))
        .collect();
    assert_eq!(warichu_lines, vec![(0, 2), (2, 4)]);
    let marks: Vec<(u32, u32)> = data
        .iter()
        .filter(|entry| entry.direction == crate::shapes::DIRECTION_EMPHASIS_MARK)
        .map(|entry| (entry.start_pos, entry.end_pos))
        .collect();
    assert_eq!(marks, vec![(0, 1), (2, 3)], "A and B are marked; 、 is not");
}

#[test]
fn horizontal_emphasis_mark_stacks_outside_auto_clearance_ruby() {
    let placement = HorizontalEmphasisPlacement {
        span: 0,
        range: 0..1,
        mark: '•',
        rect: skia::Rect::from_xywh(10.0, 40.0, 16.0, 20.0),
        em_top: 40.0,
    };
    let plain = make_span("漢", 0.0);
    let mut stacked = make_span("漢", 0.0);
    stacked.ruby = "かん".to_string();
    stacked.annotation_clearance = AnnotationClearance::Auto;

    let size = plain.font_size * EMPHASIS_FONT_SCALE;
    let ink = skia::Rect::from_wh(size, size);
    let plain_box = horizontal_emphasis_mark_box(&plain, &placement, &ink);
    let stacked_box = horizontal_emphasis_mark_box(&stacked, &placement, &ink);

    assert_eq!(plain_box.width(), plain.font_size * EMPHASIS_FONT_SCALE);
    assert_eq!(plain_box.center_x(), placement.rect.center_x());
    assert_eq!(
        stacked_box.bottom,
        plain_box.bottom - stacked.ruby_font_size(),
        "the mark sits outside the ruby layer"
    );
}

#[test]
fn horizontal_emphasis_tracks_eligible_unicode_characters() {
    init_state();
    let mut span = make_span("A😀。 B", 0.0);
    span.text_emphasis = TextEmphasis::FilledDot;
    let paragraph = make_paragraph(vec![span], 0.0);
    let mut style = skia::textlayout::TextStyle::default();
    style.set_font_size(16.0);
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_default_font_manager(skia::FontMgr::new(), None);
    let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), &fonts);
    let (texts, _) = paragraph.layout_span_texts();
    for (span, text) in paragraph.children().iter().zip(texts) {
        builder.push_style(&style);
        add_horizontal_span(&mut builder, span, &text, &[], &[], &style, &fonts);
    }
    let mut laid_out = LayoutParagraph::build(&mut builder);
    laid_out.layout(200.0);

    let placements =
        horizontal_emphasis_placements(&paragraph, &HorizontalOffsets::new(&paragraph), &laid_out);
    assert_eq!(placements.len(), 3, "A, emoji and B receive one mark each");
    assert!(placements
        .iter()
        .all(|placement| placement.rect.width() > 0.0));
    assert!(horizontal_span_style(&laid_out, &horizontal_span_ranges(&paragraph)[0]).is_some());
}

#[test]
fn horizontal_emphasis_recovers_each_non_ascii_span_style() {
    init_state();
    let mut first = make_span("漢", 0.0);
    first.text_emphasis = TextEmphasis::FilledDot;
    let mut second = make_span("字", 0.0);
    second.text_emphasis = TextEmphasis::OpenCircle;
    let paragraph = make_paragraph(vec![first, second], 0.0);
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_default_font_manager(skia::FontMgr::new(), None);
    let mut builder = ParagraphBuilder::new(&ParagraphStyle::default(), &fonts);
    let (texts, _) = paragraph.layout_span_texts();
    for (index, (span, text)) in paragraph.children().iter().zip(texts).enumerate() {
        let mut style = skia::textlayout::TextStyle::default();
        style.set_font_size(if index == 0 { 16.0 } else { 24.0 });
        builder.push_style(&style);
        add_horizontal_span(&mut builder, span, &text, &[], &[], &style, &fonts);
    }
    let mut laid_out = LayoutParagraph::build(&mut builder);
    laid_out.layout(200.0);

    let ranges = horizontal_span_ranges(&paragraph);
    assert_eq!(
        horizontal_span_style(&laid_out, &ranges[0])
            .unwrap()
            .font_size(),
        16.0
    );
    assert_eq!(
        horizontal_span_style(&laid_out, &ranges[1])
            .unwrap()
            .font_size(),
        24.0
    );
}

#[test]
fn horizontal_ruby_glyphs_share_one_baseline() {
    // JLREQ Fig 123: ー in コーデックス has its ink mid-height. Attaching
    // each glyph's own ink bottom to the base dropped it onto the base line;
    // 「 here has the same shape problem as ー.
    use crate::shapes::text_vertical::test_support::{provider, EM, VPAL_TEST_FONT};
    use crate::shapes::{FontFamily, FontStyle};
    use skia_safe::FontMgr;

    let font_provider = provider(VPAL_TEST_FONT);
    let family = format!("{}", FontFamily::new(Uuid::nil(), 400, FontStyle::Normal));
    let shaped = shape_segment_with_fallbacks(
        "あ「",
        EM / 2.0,
        &[family],
        &font_provider,
        false,
        FontFeatures::None,
        &FontMgr::from(font_provider.clone()),
        "ja",
    );
    let glyphs: Vec<RubyGlyphRef> = shaped
        .iter()
        .enumerate()
        .flat_map(|(run, shaped_run)| {
            (0..shaped_run.glyphs.len()).map(move |g| (run, g, EM / 2.0, false))
        })
        .collect();
    assert_eq!(glyphs.len(), 2);
    let span = TextSpan {
        ruby: "あ「".to_string(),
        ..make_span("漢字", 0.0)
    };
    let base = skia::Rect::from_xywh(0.0, 0.0, EM * 2.0, EM);
    let line = |glyphs: &[RubyGlyphRef]| {
        RubyLine {
            span: &span,
            shaped: &shaped,
            glyphs,
            base,
            em_top: base.top(),
            leading_gap: 0.0,
            room: (0.0, 0.0),
        }
        .baseline(0.0)
    };

    let both = line(&glyphs);
    assert_eq!(
        both,
        line(&glyphs[..1]),
        "the line sits where あ alone sits"
    );
    let alone = line(&glyphs[1..]);
    assert!(
        alone > both + EM / 20.0,
        "「 alone would have dropped onto the base: {alone} vs {both}"
    );
}

#[test]
fn latin_readings_share_one_baseline_whatever_their_letters() {
    // Pinyin over 友聊: the descender of "yu" lifted it above "lia" when each
    // reading attached its own ink bottom to the base.
    use crate::shapes::text_vertical::test_support::{provider, EM};
    use crate::shapes::{FontFamily, FontStyle};
    use skia_safe::FontMgr;

    let font_provider = provider(include_bytes!("../../fonts/sourcesanspro-regular.ttf"));
    let family = format!("{}", FontFamily::new(Uuid::nil(), 400, FontStyle::Normal));
    let size = EM / 2.0;
    let shape = |text: &str| {
        shape_segment_with_fallbacks(
            text,
            size,
            &[family.clone()],
            &font_provider,
            false,
            FontFeatures::None,
            &FontMgr::from(font_provider.clone()),
            "zh-Hans",
        )
    };
    let base = skia::Rect::from_xywh(0.0, 0.0, EM, EM);
    for side in [AnnotationSide::Over, AnnotationSide::Under] {
        let baseline = |text: &str| {
            let span = TextSpan {
                ruby: text.to_string(),
                ruby_side: side,
                font_size: EM,
                ..make_span("友", 0.0)
            };
            let shaped = shape(text);
            let glyphs = ruby_glyph_refs(text, &shaped, size);
            assert!(glyphs.iter().all(|(_, _, _, western)| *western));
            let line = RubyLine {
                span: &span,
                shaped: &shaped,
                glyphs: &glyphs,
                base,
                em_top: base.top(),
                leading_gap: 0.0,
                room: (0.0, 0.0),
            };
            (line.baseline(0.0), shaped)
        };
        let (descender, shaped) = baseline("yu");
        let (plain, _) = baseline("lia");
        assert!(
            (descender - plain).abs() < 0.01,
            "{side:?}: yu at {descender}, lia at {plain}"
        );
        if side == AnnotationSide::Over {
            let mut bounds = [skia::Rect::default()];
            shaped[0]
                .font
                .get_bounds(&shaped[0].glyphs[..1], &mut bounds, None);
            assert!(
                descender + bounds[0].bottom <= base.top() + 0.01,
                "the descender of y reaches into the base"
            );
        }
    }
}

#[test]
fn emphasis_position_overrides_the_language_and_stacks_on_its_own_side() {
    let placement = super::emphasis::HorizontalEmphasisPlacement {
        span: 0,
        range: 0..1,
        mark: '•',
        rect: skia::Rect::from_xywh(0.0, 20.0, 20.0, 20.0),
        em_top: 20.0,
    };
    for (language, position, under) in [
        ("ja", TextEmphasisPosition::Auto, false),
        ("ko", TextEmphasisPosition::Auto, false),
        ("", TextEmphasisPosition::Auto, false),
        ("zh-Hant-TW", TextEmphasisPosition::Auto, true),
        ("zh-Hans", TextEmphasisPosition::OverLeft, false),
        ("ja", TextEmphasisPosition::UnderRight, true),
    ] {
        let mut span = make_span("中文", 0.0);
        span.set_language(
            language,
            TextLayoutPolicy::for_language(language, Default::default()),
        );
        span.text_emphasis = TextEmphasis::FilledDot;
        span.text_emphasis_position = position;
        span.annotation_clearance = AnnotationClearance::Auto;
        let ink = skia::Rect::from_wh(8.0, 8.0);
        let plain = super::emphasis::horizontal_emphasis_mark_box(&span, &placement, &ink);
        assert_eq!(plain.top() >= placement.rect.bottom(), under);
        span.ruby = "zhōng wén".to_string();
        for side in [AnnotationSide::Over, AnnotationSide::Under] {
            span.ruby_side = side;
            let stacked = super::emphasis::horizontal_emphasis_mark_box(&span, &placement, &ink);
            let expected = if (side == AnnotationSide::Under) == under {
                span.ruby_font_size()
            } else {
                0.0
            };
            assert!(((stacked.top() - plain.top()).abs() - expected).abs() < 0.01);
        }
    }
}

#[test]
fn chinese_underline_stays_below_the_base_ink() {
    for position in [
        TextUnderlinePosition::Auto,
        TextUnderlinePosition::Under,
        TextUnderlinePosition::UnderRight,
    ] {
        let (mut paragraph, plan, layout) = cjk_horizontal_layout(
            &["文"],
            "zh-Hans-CN",
            crate::shapes::text_policy::KoreanWrap::Syllable,
            200.0,
        );
        let span = &mut paragraph.children_mut()[0];
        span.text_decoration = Some(skia::textlayout::TextDecoration::UNDERLINE);
        span.text_underline_position = position;
        let segments =
            crate::render::text::decoration_segments(&layout, &paragraph, &plan.offsets, 0.0, 0.0);
        assert_eq!(segments.len(), 1);
        let bottom =
            layout.get_rects_for_range(0..1, RectHeightStyle::Tight, RectWidthStyle::Tight)[0]
                .rect
                .bottom();
        assert!(segments[0].y - segments[0].thickness / 2.0 >= bottom);
    }
}

#[test]
fn cjk_underline_takes_the_paint_of_its_own_span() {
    use crate::shapes::text_vertical::test_support::{provider, EM};
    init_state();
    let provider = provider(include_bytes!("../../fonts/cjk-locl-test.ttf"));
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_asset_font_manager(Some(provider.into()));
    let dark = skia::Color::from_rgb(0x15, 0x10, 0x35);
    let pink = skia::Color::from_rgb(0xf2, 0x3a, 0xe5);
    let spans = [
        ("文文", dark, false),
        ("文文", pink, true),
        ("文", dark, false),
    ]
    .into_iter()
    .map(|(text, color, underline)| {
        let mut span = make_span(text, 0.0);
        span.font_size = EM;
        span.fills = vec![crate::shapes::Fill::Solid(crate::shapes::SolidColor(color))];
        if underline {
            span.text_decoration = Some(skia::textlayout::TextDecoration::UNDERLINE);
        }
        span
    })
    .collect();
    let mut paragraph = make_paragraph(spans, 0.0);
    paragraph.set_language("zh-Hans-CN".to_owned());
    // Three ems per line: the underlined span starts mid-line and wraps.
    let (plan, layout) = cjk_layout_paragraph(&paragraph, EM * 3.0, &fonts);
    assert_eq!(layout.get_line_metrics().len(), 2);

    let segments =
        crate::render::text::decoration_segments(&layout, &paragraph, &plan.offsets, 0.0, 0.0);
    assert_eq!(segments.len(), 2);
    for segment in segments {
        assert_eq!(segment.text_style.foreground().color(), pink);
    }
}

#[test]
fn below_emphasis_clears_an_underline_on_the_same_side() {
    let span = TextSpan {
        language: "zh-Hans".to_string(),
        layout_policy: TextLayoutPolicy::for_language("zh-Hans", Default::default()),
        text_emphasis: TextEmphasis::FilledDot,
        text_decoration: Some(skia::textlayout::TextDecoration::UNDERLINE),
        text_underline_position: TextUnderlinePosition::Under,
        annotation_clearance: AnnotationClearance::Auto,
        ..make_span("文", 0.0)
    };
    let placement = super::emphasis::HorizontalEmphasisPlacement {
        span: 0,
        range: 0..1,
        mark: '•',
        rect: skia::Rect::from_xywh(0.0, 20.0, 16.0, 18.0),
        em_top: 20.0,
    };
    let ink = skia::Rect::from_wh(8.0, 8.0);
    let mark = super::emphasis::horizontal_emphasis_mark_box(&span, &placement, &ink);
    assert!(mark.top() > placement.rect.bottom() + span.font_size / 9.0);
}

#[test]
fn chinese_horizontal_emphasis_defaults_below_the_base() {
    let span = TextSpan {
        language: "zh-Hans-CN".to_string(),
        layout_policy: TextLayoutPolicy::for_language("zh-Hans-CN", Default::default()),
        text_emphasis: TextEmphasis::FilledDot,
        ..make_span("中文", 0.0)
    };
    let placement = super::emphasis::HorizontalEmphasisPlacement {
        span: 0,
        range: 0..1,
        mark: '•',
        rect: skia::Rect::from_xywh(0.0, 20.0, 16.0, 18.0),
        em_top: 20.0,
    };
    let ink = skia::Rect::from_wh(8.0, 8.0);
    let mark = super::emphasis::horizontal_emphasis_mark_box(&span, &placement, &ink);
    assert!(mark.top() >= placement.rect.bottom());
}

#[test]
fn horizontal_pinyin_tones_keep_canonical_shaping_and_group_alignment() {
    use crate::shapes::text_vertical::test_support::{provider, EM, PINYIN_TEST_FONT};
    use crate::shapes::{FontFamily, FontStyle};

    let font_provider = provider(PINYIN_TEST_FONT);
    let family = format!("{}", FontFamily::new(Uuid::nil(), 400, FontStyle::Normal));
    let mut shapes = Vec::new();
    for reading in ["nǐ hǎo", "ni\u{30c} ha\u{30c}o"] {
        let shaped = shape_segment_with_fallbacks(
            reading,
            EM / 2.0,
            std::slice::from_ref(&family),
            &font_provider,
            false,
            FontFeatures::None,
            &FontMgr::from(font_provider.clone()),
            "zh-Hans-CN",
        );
        assert_eq!(
            shaped
                .iter()
                .map(|run| run.text.as_str())
                .collect::<String>(),
            reading
        );
        let glyphs = ruby_glyph_refs(reading, &shaped, EM / 2.0);
        assert!(glyphs.iter().all(|(_, _, _, western)| *western));
        assert!(shaped
            .iter()
            .flat_map(|run| &run.glyphs)
            .all(|glyph| *glyph != 0));
        let span = TextSpan {
            ruby: reading.to_string(),
            ..make_span("你好", 0.0)
        };
        let base = skia::Rect::from_xywh(0.0, 0.0, EM * 2.0, EM);
        let lefts = RubyLine {
            span: &span,
            shaped: &shaped,
            glyphs: &glyphs,
            base,
            em_top: base.top(),
            leading_gap: 0.0,
            room: (0.0, 0.0),
        }
        .glyph_lefts();
        for (index, pair) in lefts.windows(2).enumerate() {
            assert!((pair[1] - pair[0] - glyphs[index].2).abs() < 0.01);
        }
        let width: f32 = glyphs.iter().map(|(_, _, advance, _)| advance).sum();
        assert!((lefts[0] - (base.width() - width) / 2.0).abs() < 0.01);
        shapes.push((
            shaped
                .iter()
                .flat_map(|run| run.glyphs.clone())
                .collect::<Vec<_>>(),
            width,
        ));
    }
    assert_eq!(shapes[0].0, shapes[1].0);
    assert!((shapes[0].1 - shapes[1].1).abs() < 0.01);
}

#[test]
fn horizontal_western_ruby_keeps_its_word_whole() {
    // JLREQ Fig 112: "editor" over 編集者 is centred as one word, never
    // spread letter by letter.
    use crate::shapes::text_vertical::test_support::{provider, EM, TEST_FONT};
    use crate::shapes::{FontFamily, FontStyle};
    use skia_safe::FontMgr;

    let font_provider = provider(TEST_FONT);
    let family = format!("{}", FontFamily::new(Uuid::nil(), 400, FontStyle::Normal));
    let shaped = shape_segment_with_fallbacks(
        "editor",
        EM / 2.0,
        &[family],
        &font_provider,
        false,
        FontFeatures::None,
        &FontMgr::from(font_provider.clone()),
        "ja",
    );
    let glyphs = ruby_glyph_refs("editor", &shaped, EM / 2.0);
    assert!(glyphs.iter().all(|(_, _, _, western)| *western));
    let span = TextSpan {
        ruby: "editor".to_string(),
        ..make_span("漢字字", 0.0)
    };
    let base = skia::Rect::from_xywh(0.0, 0.0, EM * 3.0, EM);
    let lefts = RubyLine {
        span: &span,
        shaped: &shaped,
        glyphs: &glyphs,
        base,
        em_top: base.top(),
        leading_gap: 0.0,
        room: (0.0, 0.0),
    }
    .glyph_lefts();

    for (index, pair) in lefts.windows(2).enumerate() {
        assert!((pair[1] - pair[0] - glyphs[index].2).abs() < 0.01);
    }
    let word: f32 = glyphs.iter().map(|(_, _, advance, _)| advance).sum();
    let after = base.right() - (lefts[0] + word);
    assert!(
        (lefts[0] - after).abs() < 0.01,
        "centred: {} vs {after}",
        lefts[0]
    );
}

#[test]
fn horizontal_readings_never_meet_over_a_shared_kana() {
    init_state();
    // JLREQ Fig 138: に暁(あかつき)の趣(おもむき)を. The later reading may not
    // hang over the の the earlier one already covers. The test face sets
    // kana as narrow tofu, so the first reading is doubled to fill its room.
    let paragraph = make_paragraph(
        vec![
            make_span("に", 0.0),
            TextSpan {
                ruby: "あかつきあかつき".to_string(),
                ..make_span("暁", 0.0)
            },
            make_span("の", 0.0),
            TextSpan {
                ruby: "おもむき".to_string(),
                ..make_span("趣", 0.0)
            },
            make_span("を", 0.0),
        ],
        0.0,
    );
    let spacing = test_ruby_spacing(&paragraph);
    assert!(spacing.rooms[1].1 > 0.0, "あかつき may hang over の");
    assert_eq!(spacing.rooms[3].0, 0.0, "おもむき may not");
}

#[test]
fn horizontal_justify_spreads_every_line_evenly() {
    // JLREQ Fig 90: SkParagraph expands only around kanji; our spacing fills
    // each line but the last, with the same breaks, never inside a word.
    init_state();
    let text = "あいうえおかきくABCけこさしすせそたちつてとなにぬねの";
    let paragraph = make_paragraph(vec![make_span(text, 0.0)], 0.0);
    let plan = HorizontalParagraphPlan::new(&paragraph);
    let width = 16.0 * 7.6;
    let mut style = skia::textlayout::TextStyle::default();
    style.set_font_size(16.0);
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_default_font_manager(skia::FontMgr::new(), None);
    // As `paragraph_builders` sets it for justified Japanese paragraphs.
    let mut paragraph_style = ParagraphStyle::default();
    paragraph_style.set_apply_rounding_hack(false);
    let lay_out = |extra: &[(usize, f32)]| {
        let mut builder = ParagraphBuilder::new(&paragraph_style, &fonts);
        builder.push_style(&style);
        add_horizontal_span(
            &mut builder,
            &paragraph.children()[0],
            &plan.texts[0],
            &plan.pair_spacing[0],
            extra,
            &style,
            &fonts,
        );
        let mut laid_out = LayoutParagraph::build(&mut builder);
        laid_out.layout(width);
        laid_out
    };

    let natural = lay_out(&[]);
    let spacing = horizontal_jlreq_justify_spacing(&paragraph, &natural, &plan, width);
    let justified = lay_out(&spacing[0]);

    let natural_lines = natural.get_line_metrics();
    let lines = justified.get_line_metrics();
    assert!(lines.len() > 2, "the text wraps");
    assert_eq!(lines.len(), natural_lines.len(), "same line count");
    for (line, before) in lines.iter().zip(&natural_lines) {
        assert_eq!(line.start_index, before.start_index, "same breaks");
    }
    let (last, full) = lines.split_last().unwrap();
    for line in full {
        assert!(
            (line.width as f32 - width).abs() < 0.2,
            "line width {}",
            line.width
        );
    }
    assert!((last.width - natural_lines.last().unwrap().width).abs() < 0.01);

    let chars: Vec<char> = plan.texts[0].chars().collect();
    for (index, _) in &spacing[0] {
        let pair = (chars[*index], chars.get(index + 1).copied().unwrap_or(' '));
        assert!(
            !(pair.0.is_ascii_alphabetic() && pair.1.is_ascii_alphabetic()),
            "no space inside ABC: {pair:?}"
        );
    }
}

#[test]
fn horizontal_squeeze_keeps_the_next_character_on_the_line() {
    // JLREQ Fig 90: a line slightly too long squeezes its comma aki instead
    // of moving its last character down.
    init_state();
    let text = "あい、うえ、おかきくけこさしすせそ";
    let paragraph = make_paragraph(vec![make_span(text, 0.0)], 0.0);
    let plan = HorizontalParagraphPlan::new(&paragraph);
    let mut style = skia::textlayout::TextStyle::default();
    style.set_font_size(16.0);
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_default_font_manager(skia::FontMgr::new(), None);
    let mut paragraph_style = ParagraphStyle::default();
    paragraph_style.set_apply_rounding_hack(false);
    let advance = {
        let mut builder = ParagraphBuilder::new(&paragraph_style, &fonts);
        builder.push_style(&style);
        builder.add_text("あ");
        let mut probe = LayoutParagraph::build(&mut builder);
        probe.layout(1000.0);
        probe.max_intrinsic_width()
    };
    // Seven characters fit; the eighth overflows by less than the 、 aki.
    let width = advance * 7.6;
    let lay_out = |extra: &[Vec<(usize, f32)>], window: std::ops::Range<usize>| {
        let sheds: Vec<(usize, f32)> = plan.pair_spacing[0]
            .iter()
            .chain(extra.first().into_iter().flatten())
            .copied()
            .collect();
        let (text, from) =
            window_slice(&plan.texts[0], &plan.offsets.ranges[0], &window).unwrap_or(("", 0));
        let sheds = window_entries(&sheds, from, text.chars().count());
        let mut builder = ParagraphBuilder::new(&paragraph_style, &fonts);
        builder.push_style(&style);
        add_horizontal_span(
            &mut builder,
            &paragraph.children()[0],
            text,
            &sheds,
            &[],
            &style,
            &fonts,
        );
        let mut laid_out = LayoutParagraph::build(&mut builder);
        laid_out.layout(width);
        laid_out
    };

    // Line ends count builder UTF-16 units, word joiners included.
    let builder_text: Vec<char> = plan.texts[0].chars().collect();
    let natural = lay_out(&[], FULL_WINDOW);
    let natural_end = natural.get_line_metrics()[0].end_index;
    assert_eq!(
        builder_text[natural_end], 'か',
        "the natural line ends before か"
    );
    let (sheds, squeezed) = horizontal_squeeze_sheds(
        &paragraph,
        &plan,
        width,
        crate::shapes::LineAdjustment::PushInFirst,
        &lay_out,
    );

    assert_eq!(
        squeezed.get_line_metrics()[0].end_index,
        natural_end + 1,
        "か joins the line"
    );
    let squeezed_chars: Vec<usize> = sheds[0].iter().map(|(index, _)| *index).collect();
    let commas: Vec<usize> = builder_text
        .iter()
        .enumerate()
        .filter(|(_, ch)| **ch == '、')
        .map(|(index, _)| index)
        .collect();
    assert_eq!(squeezed_chars, commas, "both commas give up aki");
    assert!((sheds[0][0].1 - sheds[0][1].1).abs() < 0.01, "equally");
}

#[test]
fn horizontal_zhuyin_reserves_its_right_strip_without_changing_line_leading() {
    init_state();
    for reading in ["ㄓ", "ㄋㄧˇ", "˙ㄉㄜ", "ㄓㄨㄤˋ"] {
        let span = TextSpan {
            ruby: reading.into(),
            ruby_zhuyin: true,
            annotation_clearance: AnnotationClearance::Auto,
            ..make_span("中", 0.0)
        };
        let paragraph = make_paragraph(vec![span.clone(), make_span("文", 0.0)], 0.0);
        let spacing = test_ruby_spacing(&paragraph);
        assert_eq!(spacing.adjustments[0], vec![(0, span.font_size * 0.6)]);
        assert_eq!(span.annotation_room_em(), 0.0);
        let rect = skia::Rect::from_xywh(0.0, 0.0, span.font_size * 1.6, span.font_size);
        let origin = horizontal_zhuyin_origin(&span, rect, 0.0);
        assert!((origin.x - span.font_size).abs() < 0.001);
        assert_eq!(origin.y, 0.0);
    }
}

#[test]
fn horizontal_annotations_attach_to_the_ideographic_em_box() {
    use crate::shapes::text_vertical::test_support::{provider, EM, VMTX_TEST_FONT};
    init_state();
    // Noto metrics: hhea ascent/descent 1.16/0.288 em, em box 0.88/0.12 em.
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_asset_font_manager(Some(provider(VMTX_TEST_FONT).into()));
    // The em box depends on the base alone; a reading would shape it through
    // the global font resources.
    let mut span = make_span("中", 0.0);
    span.font_size = EM;
    span.text_emphasis = TextEmphasis::FilledDot;
    let paragraph = make_paragraph(vec![span], 0.0);
    let (plan, layout) = cjk_layout_paragraph(&paragraph, EM * 10.0, &fonts);
    let range = &plan.offsets.ranges[0];
    let rect = layout.get_rects_for_range(
        range.builder_start..range.builder_end,
        RectHeightStyle::Tight,
        RectWidthStyle::Tight,
    )[0]
    .rect;
    let lines = layout.get_line_metrics();
    let em_top = horizontal_base_em_top(&layout, &lines, range.builder_start, EM, rect);
    let baseline = lines[0].baseline as f32;
    assert!((em_top - (baseline - 0.88 * EM)).abs() < 0.01);

    let placements = horizontal_emphasis_placements(&paragraph, &plan.offsets, &layout);
    assert_eq!(placements.len(), 1);
    assert!((placements[0].em_top - em_top).abs() < 0.01);
}

#[test]
fn horizontal_indivisible_punctuation_unit_overflows_a_narrow_box() {
    use crate::shapes::text_policy::KoreanWrap;
    for language in ["zh-Hant-TW", "zh-Hans-CN", "zh-Hant-HK", "ko", "ja"] {
        let (_, plan, layout) =
            cjk_horizontal_layout(&["文", "。"], language, KoreanWrap::KeepWords, 20.0);
        let starts: Vec<_> = layout
            .get_line_metrics()
            .iter()
            .map(|line| plan.offsets.builder_to_source(line.start_index))
            .collect();
        assert_eq!(
            starts,
            vec![0],
            "{language}: a closing mark must stay with its base"
        );
        assert!(
            layout.longest_line() > 20.0,
            "the unit overflows rather than splitting"
        );
    }
}

#[test]
fn japanese_narrow_box_keeps_ruby_bases_whole_and_respects_kinsoku() {
    use crate::shapes::jlreq::forbidden_at_line_start;
    use crate::shapes::text_vertical::test_support::provider;
    init_state();
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_asset_font_manager(Some(
        provider(include_bytes!("../../fonts/cjk-locl-test.ttf")).into(),
    ));
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
    let mut base = make_span("文文", 0.0);
    base.font_size = 20.0;
    base.ruby = "ぶんぶん".to_string();
    let mut paragraph = make_paragraph(
        vec![make_span("「", 0.0), base, make_span("」、文文。", 0.0)],
        0.0,
    );
    paragraph.set_language("ja".to_string());
    let source: Vec<char> = "「文文」、文文。".chars().collect();
    let (plan, layout) = cjk_layout_paragraph(&paragraph, 20.0, &fonts);
    let starts: Vec<usize> = layout
        .get_line_metrics()
        .iter()
        .map(|line| plan.offsets.builder_to_source(line.start_index))
        .collect();
    for start in &starts {
        assert!(
            !forbidden_at_line_start(source[*start]),
            "line {starts:?} starts with {}",
            source[*start]
        );
    }
    let targets = horizontal_ruby_targets(&paragraph, &plan.offsets);
    assert_eq!(targets.len(), 1);
    let rects = layout.get_rects_for_range(
        targets[0].1.clone(),
        RectHeightStyle::Tight,
        RectWidthStyle::Tight,
    );
    assert!(
        rects
            .iter()
            .all(|r| (r.rect.top() - rects[0].rect.top()).abs() < 0.01),
        "the ruby base splits over lines {starts:?}"
    );
}

#[test]
fn narrow_atomic_overflow_retains_normal_wrap_budget_and_explicit_newlines() {
    use crate::shapes::text_policy::KoreanWrap;
    for language in ["zh-Hant-TW", "zh-Hans-CN", "zh-Hant-HK", "ko"] {
        for (texts, expected) in [
            (vec!["文文文", "。", "文文文"], vec![0, 1, 2, 4, 5, 6]),
            (vec!["文\n", "文", "。", "\n文"], vec![0, 2, 5]),
            (vec!["文（", "文）", "文文"], vec![0, 1, 4, 5]),
        ] {
            let (paragraph, plan, layout) =
                cjk_horizontal_layout(&texts, language, KoreanWrap::KeepWords, 20.0);
            let starts: Vec<_> = layout
                .get_line_metrics()
                .iter()
                .map(|line| plan.offsets.builder_to_source(line.start_index))
                .collect();
            assert_eq!(starts, expected, "{language}: {texts:?}");
            for at in 0..=paragraph
                .children()
                .iter()
                .map(|s| s.text.chars().count())
                .sum()
            {
                assert_eq!(
                    plan.offsets
                        .builder_to_source(plan.offsets.source_to_builder(at)),
                    at
                );
            }
            assert_eq!(
                paragraph
                    .children()
                    .iter()
                    .map(|s| s.text.as_str())
                    .collect::<String>(),
                texts.concat()
            );
        }
    }
}

#[test]
fn narrow_atomic_units_preserve_controls_numeric_expressions_and_graphemes() {
    use crate::shapes::text_policy::KoreanWrap;
    for language in ["zh-Hant-TW", "zh-Hans-CN", "zh-Hant-HK", "ko"] {
        for unit in [
            "文。",
            "（文）",
            "$1.5%",
            "1℃",
            "文\u{a0}文",
            "文\u{2060}文",
            "e\u{301}。",
            "😀。",
            "👩‍👩‍👧‍👦。",
        ] {
            for width in [1.0, 10.0, 20.0, 29.0, 39.0] {
                let source = format!("文文{unit}文文");
                let (_, plan, layout) =
                    cjk_horizontal_layout(&[&source], language, KoreanWrap::KeepWords, width);
                let end = 2 + unit.chars().count();
                for line in layout.get_line_metrics().iter().skip(1) {
                    let at = plan.offsets.builder_to_source(line.start_index);
                    assert!(
                        !(2 < at && at < end),
                        "{language} {width}px breaks {unit} at {at}"
                    );
                }
                assert!(
                    layout
                        .get_line_metrics()
                        .iter()
                        .all(|line| line.width > 0.0),
                    "no synthetic empty lines in {source}"
                );
                for at in 0..=source.chars().count() {
                    assert_eq!(
                        plan.offsets
                            .builder_to_source(plan.offsets.source_to_builder(at)),
                        at,
                        "{source} at {at}"
                    );
                }
            }
        }
    }
}

#[test]
fn narrow_atomic_overflow_keeps_ruby_and_warichu_with_their_closing_marks() {
    use crate::shapes::text_vertical::test_support::provider;
    init_state();
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_asset_font_manager(Some(
        provider(include_bytes!("../../fonts/cjk-locl-test.ttf")).into(),
    ));
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
    for warichu in [false, true] {
        let mut annotation = make_span("文文文文", 0.0);
        annotation.font_size = 20.0;
        annotation.warichu = warichu;
        if !warichu {
            annotation.ruby = "wén wén".to_string();
        }
        let mut paragraph = make_paragraph(
            vec![make_span("文文", 0.0), annotation, make_span("。文文", 0.0)],
            0.0,
        );
        paragraph.set_language("zh-Hant-TW".to_string());
        let (plan, layout) = cjk_layout_paragraph(&paragraph, 20.0, &fonts);
        let starts: Vec<_> = layout
            .get_line_metrics()
            .iter()
            .map(|line| plan.offsets.builder_to_source(line.start_index))
            .collect();
        assert_eq!(starts, vec![0, 1, 2, 7, 8], "warichu={warichu}");
        for at in [0, 1, 2, 6, 7, 8, 9] {
            assert_eq!(
                plan.offsets
                    .builder_to_source(plan.offsets.source_to_builder(at)),
                at,
                "warichu={warichu}, {at}"
            );
        }
        assert!(
            horizontal_span_style(&layout, &plan.offsets.ranges[1]).is_some(),
            "warichu={warichu}"
        );
        if !warichu {
            let targets = horizontal_ruby_targets(&paragraph, &plan.offsets);
            assert_eq!(targets.len(), 1);
            let rects = layout.get_rects_for_range(
                targets[0].1.clone(),
                RectHeightStyle::Tight,
                RectWidthStyle::Tight,
            );
            assert!(rects
                .iter()
                .all(|r| (r.rect.top() - rects[0].rect.top()).abs() < 0.01));
        }
    }
}

#[test]
fn adjacent_horizontal_ruby_bases_retain_each_annotation_style() {
    use crate::shapes::text_vertical::test_support::provider;
    init_state();
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let mut fonts = resources.fonts.font_collection().clone();
    fonts.set_asset_font_manager(Some(
        provider(include_bytes!("../../fonts/cjk-locl-test.ttf")).into(),
    ));
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
    for width in [240.0, 48.0] {
        let spans = ["文", "文", "文", "文"]
            .iter()
            .map(|text| TextSpan {
                text: text.to_string(),
                font_size: 24.0,
                ruby: "wén".to_string(),
                text_emphasis: TextEmphasis::FilledDot,
                annotation_clearance: AnnotationClearance::Auto,
                ..make_span(text, 0.0)
            })
            .collect();
        let mut paragraph = make_paragraph(spans, 0.0);
        paragraph.set_language("zh-Hant-TW".to_string());
        let (plan, layout) = cjk_layout_paragraph(&paragraph, width, &fonts);
        assert_eq!(
            horizontal_emphasis_placements(&paragraph, &plan.offsets, &layout).len(),
            4
        );
        for range in &plan.offsets.ranges {
            let style = horizontal_span_style(&layout, range);
            assert!(
                style.is_some(),
                "missing style for adjacent ruby base {} at {width}px",
                range.span
            );
            assert_eq!(style.unwrap().font_size(), 24.0);
        }
    }
}

#[test]
fn adjacent_ruby_emphasis_paints_a_mark_for_every_base_on_either_side() {
    init_state();
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let fonts = resources.fonts.font_collection().clone();
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
    for position in [
        TextEmphasisPosition::OverRight,
        TextEmphasisPosition::UnderRight,
    ] {
        let spans = ["A", "B", "C", "D"]
            .iter()
            .map(|text| TextSpan {
                font_size: 48.0,
                fills: vec![crate::shapes::Fill::Solid(crate::shapes::SolidColor(
                    skia::Color::BLACK,
                ))],
                ruby: "a".to_string(),
                text_emphasis: TextEmphasis::FilledDot,
                text_emphasis_position: position,
                annotation_clearance: AnnotationClearance::Auto,
                ..make_span(text, 0.0)
            })
            .collect();
        let mut paragraph = make_paragraph(spans, 0.0);
        paragraph.set_language("zh-Hant-TW".to_string());
        let (plan, layout) = cjk_layout_paragraph(&paragraph, 400.0, &fonts);
        let placements = horizontal_emphasis_placements(&paragraph, &plan.offsets, &layout);
        assert_eq!(placements.len(), 4);
        let mut surface = skia::surfaces::raster_n32_premul((440, 200)).unwrap();
        surface.canvas().clear(skia::Color::TRANSPARENT);
        paint_horizontal_emphasis(surface.canvas(), &paragraph, &plan, &layout, 20.0, 40.0);
        let image = surface.image_snapshot();
        let pixels = image.peek_pixels().expect("raster pixels");
        for placement in placements {
            let left = (20.0 + placement.rect.left()).ceil() as i32;
            let right = (20.0 + placement.rect.right()).floor() as i32;
            let ink = (0..200).any(|y| (left..right).any(|x| pixels.get_color((x, y)).a() > 0));
            assert!(
                ink,
                "missing {position:?} emphasis on adjacent ruby base {}",
                placement.span
            );
        }
    }
}

#[test]
fn annotation_style_keeps_utf16_lines_separate_from_utf8_style_keys() {
    use crate::shapes::text_vertical::test_support::{provider, TEST_FONT};
    init_state();
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let mut fonts = resources.fonts.font_collection().clone();
    fonts.set_asset_font_manager(Some(provider(TEST_FONT).into()));
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
    let prefix = make_span("😀\n", 0.0);
    let spans = vec![
        prefix,
        TextSpan {
            font_size: 24.0,
            ruby: "a".to_string(),
            ..make_span("A", 0.0)
        },
        TextSpan {
            font_size: 24.0,
            ruby: "b".to_string(),
            ..make_span("B", 0.0)
        },
    ];
    let mut paragraph = make_paragraph(spans, 0.0);
    paragraph.set_language("zh-Hant-TW".to_string());
    let (plan, layout) = cjk_layout_paragraph(&paragraph, 240.0, &fonts);
    for (range, expected) in plan.offsets.ranges.iter().zip([16.0, 24.0, 24.0]) {
        let style = horizontal_span_style(&layout, range).expect("style on the base's line");
        assert_eq!(style.font_size(), expected);
    }
}

#[test]
fn horizontal_emphasis_export_bounds_include_every_adjacent_ruby_mark() {
    init_state();
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
    for grow in [
        crate::shapes::GrowType::Fixed,
        crate::shapes::GrowType::AutoHeight,
        crate::shapes::GrowType::AutoWidth,
    ] {
        let spans = ["A", "B", "C", "D"]
            .iter()
            .map(|text| TextSpan {
                font_size: 48.0,
                language: "zh-Hant-TW".to_string(),
                layout_policy: TextLayoutPolicy::for_language("zh-Hant-TW", Default::default()),
                ruby: "a".to_string(),
                text_emphasis: TextEmphasis::FilledDot,
                text_decoration: Some(skia::textlayout::TextDecoration::UNDERLINE),
                annotation_clearance: AnnotationClearance::Auto,
                ..make_span(text, 0.0)
            })
            .collect();
        let mut paragraph = make_paragraph(spans, 0.0);
        paragraph.set_language("zh-Hant-TW".to_string());
        let bounds = crate::math::Rect::from_xywh(0.0, 0.0, 360.0, 160.0);
        let mut content = TextContent::new(bounds, grow);
        content.add_paragraph(paragraph);
        content.update_layout(bounds);
        let mut shape = crate::shapes::Shape::new(Uuid::nil());
        shape.set_selrect(0.0, 0.0, 360.0, 160.0);
        let export = content.calculate_bounds(&shape, false).to_rect();
        let plans = content.horizontal_plans();
        let paragraph = &content.paragraphs()[0];
        let marks = horizontal_emphasis_mark_boxes(
            paragraph,
            &plans[0].offsets,
            &content.layout.paragraphs[0][0],
        );
        assert_eq!(marks.len(), 4);
        for (_, mark) in marks {
            assert!(
                export.bottom() >= mark.bottom(),
                "{grow:?}: export {export:?} crops mark {mark:?}"
            );
        }
    }
}

#[test]
fn horizontal_ruby_export_bounds_keep_every_reading_whole() {
    // Readings sit above the line box with the Japanese default clearance.
    // Auto-width text built its bounds from the content rect and the
    // emphasis marks only, so an export cut the top of the reading.
    init_state();
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
    let grows = [
        crate::shapes::GrowType::Fixed,
        crate::shapes::GrowType::AutoHeight,
        crate::shapes::GrowType::AutoWidth,
    ];
    let emphases = [TextEmphasis::None, TextEmphasis::FilledCircle];
    let texts = [("AB", "ABC", "CD"), ("漢字", "かんじ", "の圏点")];
    for (grow, emphasis, (base, ruby, rest)) in grows
        .into_iter()
        .flat_map(|grow| emphases.map(|emphasis| (grow, emphasis)))
        .flat_map(|(grow, emphasis)| texts.map(|text| (grow, emphasis, text)))
    {
        let spans = vec![
            TextSpan {
                font_size: 48.0,
                fills: vec![crate::shapes::Fill::Solid(crate::shapes::SolidColor(
                    skia::Color::BLACK,
                ))],
                ruby: ruby.to_string(),
                text_emphasis: emphasis,
                ..make_span(base, 0.0)
            },
            make_span(rest, 0.0),
        ];
        let mut paragraph = make_paragraph(spans, 0.0);
        paragraph.set_language("ja".to_string());
        let bounds = crate::math::Rect::from_xywh(0.0, 0.0, 360.0, 60.0);
        let mut content = TextContent::new(bounds, grow);
        content.add_paragraph(paragraph);
        content.update_layout(bounds);
        let mut shape = crate::shapes::Shape::new(Uuid::nil());
        shape.set_selrect(0.0, 0.0, 360.0, 60.0);
        let export = content.calculate_bounds(&shape, false).to_rect();

        let pad = 100.0;
        let mut surface = skia::surfaces::raster_n32_premul((560, 260)).unwrap();
        surface.canvas().clear(skia::Color::TRANSPARENT);
        let x = pad + content.horizontal_plans()[0].x_offset(&content.paragraphs()[0], 360.0);
        paint_horizontal_ruby(
            surface.canvas(),
            &content,
            0,
            &content.layout.paragraphs[0][0],
            x,
            pad,
        );
        let image = surface.image_snapshot();
        let pixels = image.peek_pixels().expect("raster pixels");
        let ink_top = (0..260)
            .find(|y| (0..560).any(|x| pixels.get_color((x, *y)).a() > 0))
            .expect("the reading is painted") as f32;
        assert!(
            ink_top - pad >= export.top() - 1.0,
            "{grow:?} {emphasis:?} {ruby}: export top {} crops the reading inked from {}",
            export.top(),
            ink_top - pad
        );
    }
}

/// Fixed-width `TextContent` of one paragraph, laid out like the renderer.
fn aligned_content(text: &str, tracking: f32, align: TextAlign, width: f32) -> TextContent {
    let mut paragraph = Paragraph::new(
        align,
        TextDirection::LTR,
        None,
        None,
        1.0,
        tracking,
        vec![TextSpan {
            fills: vec![crate::shapes::Fill::Solid(crate::shapes::SolidColor(
                skia::Color::BLACK,
            ))],
            ..make_span(text, tracking)
        }],
    );
    paragraph.set_language("zh-Hans".to_string());
    let bounds = crate::math::Rect::from_xywh(0.0, 0.0, width, 200.0);
    let mut content = TextContent::new(bounds, crate::shapes::GrowType::Fixed);
    content.add_paragraph(paragraph);
    content.update_layout(bounds);
    content
}

#[test]
fn spread_lines_start_and_end_at_the_box_edges() {
    use skia::textlayout::{RectHeightStyle, RectWidthStyle};
    init_state();
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
    for (align, text, width) in [
        (TextAlign::Distribute, "国家", 200.0),
        (TextAlign::Distribute, "手机号", 200.0),
        (TextAlign::Justify, "国家国家国家国家国家国家国家国家", 50.0),
    ] {
        let content = aligned_content(text, 0.0, align, width);
        let layout = &content.layout.paragraphs[0][0];
        let lines = layout.get_line_metrics();
        let spread = if align == TextAlign::Distribute {
            lines.len()
        } else {
            lines.len() - 1
        };
        assert!(spread > 0, "{text}: no spread line");
        let rect = |at: usize| {
            layout.get_rects_for_range(at..at + 1, RectHeightStyle::Tight, RectWidthStyle::Tight)[0]
                .rect
        };
        for line in lines.iter().take(spread) {
            let first = rect(line.start_index);
            let last = rect(line.end_excluding_whitespaces - 1);
            assert!(
                first.left.abs() < 0.1,
                "{text}: line starts at {}",
                first.left
            );
            assert!(line.left.abs() < 0.1, "{text}: line left {}", line.left);
            assert!(
                (last.right - width).abs() < 0.1,
                "{text}: line ends at {}, box {width}",
                last.right
            );
            let hit = layout.get_glyph_position_at_coordinate((first.left + 1.0, first.center_y()));
            assert_eq!(hit.position as usize, line.start_index, "{text}: hit test");
        }
        // The painted ink starts at the box edge too, not half a gap in.
        let mut surface = skia::surfaces::raster_n32_premul((400, 200)).unwrap();
        layout.paint(surface.canvas(), (100.0, 0.0));
        let pixels = surface.peek_pixels().unwrap();
        let ink_left = (0..400)
            .find(|x| (0..200).any(|y| pixels.get_color((*x, y)).a() > 0))
            .expect("visible ink");
        assert!(
            (100..104).contains(&ink_left),
            "{text}: ink starts at {}",
            ink_left - 100
        );
    }
    // Span tracking keeps SkParagraph's half spacing before the line.
    let content = aligned_content("国家", 10.0, TextAlign::Left, 200.0);
    let layout = &content.layout.paragraphs[0][0];
    let first = layout.get_rects_for_range(0..1, RectHeightStyle::Tight, RectWidthStyle::Tight)[0];
    assert!(
        (first.rect.left - 5.0).abs() < 0.01,
        "tracked line starts at {}",
        first.rect.left
    );
}

/// Japanese text distributes like the other languages (均等割り付け).
#[test]
fn japanese_distribution_reaches_the_box_edges() {
    use skia::textlayout::{RectHeightStyle, RectWidthStyle};
    init_state();
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
    for text in ["日本", "にほんご", "東京都"] {
        let mut paragraph = Paragraph::new(
            TextAlign::Distribute,
            TextDirection::LTR,
            None,
            None,
            1.0,
            0.0,
            vec![TextSpan {
                fills: vec![crate::shapes::Fill::Solid(crate::shapes::SolidColor(
                    skia::Color::BLACK,
                ))],
                ..make_span(text, 0.0)
            }],
        );
        paragraph.set_language("ja".to_string());
        let bounds = crate::math::Rect::from_xywh(0.0, 0.0, 200.0, 200.0);
        let mut content = TextContent::new(bounds, crate::shapes::GrowType::Fixed);
        content.add_paragraph(paragraph);
        content.update_layout(bounds);
        let layout = &content.layout.paragraphs[0][0];
        let line = &layout.get_line_metrics()[0];
        let last = layout.get_rects_for_range(
            line.end_excluding_whitespaces - 1..line.end_excluding_whitespaces,
            RectHeightStyle::Tight,
            RectWidthStyle::Tight,
        )[0]
        .rect;
        assert!(
            (last.right - 200.0).abs() < 0.1,
            "{text}: ends at {}",
            last.right
        );
    }
}

#[test]
fn horizontal_emphasis_mark_box_is_the_painted_mark_on_either_side() {
    init_state();
    let mut resources =
        crate::render::RenderResources::try_new_headless().expect("headless resources");
    let fonts = resources.fonts.font_collection().clone();
    let _guard = crate::globals::TestRenderResourcesGuard::install(&mut resources);
    for position in [
        TextEmphasisPosition::OverRight,
        TextEmphasisPosition::UnderRight,
    ] {
        let span = TextSpan {
            font_size: 48.0,
            fills: vec![crate::shapes::Fill::Solid(crate::shapes::SolidColor(
                skia::Color::BLACK,
            ))],
            text_emphasis: TextEmphasis::FilledCircle,
            text_emphasis_position: position,
            ..make_span("A", 0.0)
        };
        let paragraph = make_paragraph(vec![span], 0.0);
        let (plan, layout) = cjk_layout_paragraph(&paragraph, 400.0, &fonts);
        let marks = horizontal_emphasis_mark_boxes(&paragraph, &plan.offsets, &layout);
        assert_eq!(marks.len(), 1);
        let (placement, mark_box) = &marks[0];
        let span = &paragraph.children()[0];
        let shaped = super::emphasis::HorizontalEmphasisMark::measure(
            &layout,
            &plan.offsets.ranges[0],
            span,
            placement.mark,
        )
        .expect("shaped mark");
        assert_eq!(mark_box.width(), shaped.ink.width());
        assert_eq!(mark_box.height(), shaped.ink.height());
        assert_eq!(mark_box.center_x(), placement.rect.center_x());
        let offset = span.emphasis_outside_offset(false);
        if position == TextEmphasisPosition::OverRight {
            assert_eq!(mark_box.bottom(), placement.em_top - offset);
        } else {
            assert_eq!(mark_box.top(), placement.rect.bottom() + offset);
        }

        let (x, y) = (20.0, 60.0);
        let mut surface = skia::surfaces::raster_n32_premul((440, 220)).unwrap();
        surface.canvas().clear(skia::Color::TRANSPARENT);
        paint_horizontal_emphasis(surface.canvas(), &paragraph, &plan, &layout, x, y);
        let image = surface.image_snapshot();
        let pixels = image.peek_pixels().expect("raster pixels");
        let mut ink: Option<skia::IRect> = None;
        for py in 0..220 {
            for px in 0..440 {
                if pixels.get_color((px, py)).a() > 0 {
                    let pixel = skia::IRect::from_xywh(px, py, 1, 1);
                    ink = Some(ink.map_or(pixel, |rect| skia::IRect::join(&rect, &pixel)));
                }
            }
        }
        let ink = ink.expect("the mark paints");
        let painted = mark_box.with_offset((x, y));
        assert!(
            ink.left as f32 >= painted.left.floor()
                && ink.right as f32 <= painted.right.ceil()
                && ink.top as f32 >= painted.top.floor()
                && ink.bottom as f32 <= painted.bottom.ceil(),
            "{position:?}: mark paints {ink:?} outside its box {painted:?}"
        );
    }
}

/// Gaps, in em, of the first line of ああAああPenpot justified horizontally
/// with `extra_em` of slack on that line: (ああ, あA, Aあ, ああ).
fn horizontal_justified_gaps(extra_em: f32) -> Vec<f32> {
    use crate::shapes::text_vertical::test_support::{
        provider_with_fallback, EM, TEST_FONT, VPAL_TEST_FONT,
    };
    init_state();
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_asset_font_manager(Some(
        provider_with_fallback(VPAL_TEST_FONT, TEST_FONT).into(),
    ));
    let span = TextSpan {
        font_size: EM,
        ..make_span("ああAああPenpot", 0.0)
    };
    let paragraph = make_paragraph(vec![span], 0.0);
    let (plan, wide) = cjk_layout_paragraph(&paragraph, 1000.0, &fonts);
    // Builder offset after the fifth real character, past both inserted
    // inter-script spaces.
    let texts: Vec<char> = plan.texts[0].chars().collect();
    let fifth = texts
        .iter()
        .enumerate()
        .filter(|(_, ch)| **ch != line_breaks::JAPANESE_WESTERN_SPACE)
        .nth(4)
        .map(|(index, _)| index)
        .unwrap();
    let five =
        wide.get_rects_for_range(0..fifth + 1, RectHeightStyle::Tight, RectWidthStyle::Tight);
    let five_width = five.last().unwrap().rect.right() - five[0].rect.left();
    let width = five_width + extra_em * EM;
    let (plan, natural) = cjk_layout_paragraph(&paragraph, width, &fonts);
    let lines = natural.get_line_metrics();
    assert_eq!(lines.len(), 2, "Penpot moves to the second line");
    let spacing = horizontal_jlreq_justify_spacing(&paragraph, &natural, &plan, width);
    let at = |index: usize| -> f32 {
        spacing[0]
            .iter()
            .filter(|(at, _)| *at == index)
            .map(|(_, amount)| amount)
            .sum::<f32>()
            / EM
    };
    // あ0 あ1 U+2005 A3 U+2005 あ5 あ6: each gap takes the spacing of the
    // character before it.
    assert_eq!(texts[2], line_breaks::JAPANESE_WESTERN_SPACE);
    vec![at(0), at(1), at(3), at(5)]
}

/// The same gaps of ああAああPenpot justified as a vertical column.
fn vertical_justified_gaps(extra_em: f32) -> Vec<f32> {
    use crate::shapes::text_vertical::test_support::{
        layout_with_fallback, provider_with_fallback, spans_content, vertical_paragraph, EM,
        TEST_FONT, VPAL_TEST_FONT,
    };
    let provider = provider_with_fallback(VPAL_TEST_FONT, TEST_FONT);
    let fallback = ["fallback".to_string()];
    let content = |align: TextAlign, height: f32| {
        let span = TextSpan {
            text: "ああAああPenpot".to_string(),
            font_size: EM,
            line_height: 1.0,
            ..TextSpan::default()
        };
        let mut content = spans_content(vec![], height);
        *content.paragraphs_mut() = vec![vertical_paragraph(vec![span], align, 1.0)];
        content
    };
    let wide = layout_with_fallback(
        &provider,
        &content(TextAlign::Left, 1000.0),
        1000.0,
        &fallback,
    );
    let five: f32 = wide.cells[..5].iter().map(|cell| cell.extent).sum();
    let height = five + extra_em * EM;
    let layout = layout_with_fallback(
        &provider,
        &content(TextAlign::Justify, height),
        height,
        &fallback,
    );
    assert!(layout.cells[..5].iter().all(|cell| cell.column == 0));
    assert!(layout.cells[5..].iter().all(|cell| cell.column == 1));
    (0..4)
        .map(|i| {
            let (cell, next) = (&layout.cells[i], &layout.cells[i + 1]);
            let natural = wide.cells[i + 1].top - wide.cells[i].top;
            (next.top - cell.top - natural) / EM
        })
        .collect()
}

#[test]
fn horizontal_and_vertical_justify_widen_gaps_in_the_same_jlreq_order() {
    for (axis, gaps) in [
        (
            "horizontal",
            horizontal_justified_gaps as fn(f32) -> Vec<f32>,
        ),
        ("vertical", vertical_justified_gaps),
    ] {
        // Little slack: only the two Japanese/Western gaps widen.
        let small = gaps(0.3);
        assert!(
            small[1] > 0.1 && (small[1] - small[2]).abs() < 0.01,
            "{axis}: {small:?}"
        );
        assert!(
            small[0].abs() < 0.001 && small[3].abs() < 0.001,
            "{axis}: {small:?}"
        );
        // More slack: they stop at their half-em maximum (a quarter em past
        // the preferred quarter), then the kana gaps share the rest.
        let large = gaps(0.9);
        assert!(
            (large[1] - 0.25).abs() < 0.01 && (large[2] - 0.25).abs() < 0.01,
            "{axis}: {large:?}"
        );
        assert!(
            large[0] > 0.1 && (large[0] - large[3]).abs() < 0.01,
            "{axis}: {large:?}"
        );
    }
}

/// `paragraph` with the plan's sheds plus `extra`, restricted to the builder
/// `window`, laid out at `width` as the squeeze pass measures it.
fn squeeze_window_layout(
    paragraph: &Paragraph,
    plan: &HorizontalParagraphPlan,
    fonts: &skia::textlayout::FontCollection,
    width: f32,
    extra: &[Vec<(usize, f32)>],
    window: std::ops::Range<usize>,
) -> LayoutParagraph {
    let mut paragraph_style = paragraph.paragraph_to_style();
    paragraph_style.set_apply_rounding_hack(false);
    let mut builder = ParagraphBuilder::new(&paragraph_style, fonts);
    let span = &paragraph.children()[0];
    let style = span.to_style(
        &crate::math::Rect::from_xywh(0.0, 0.0, width, 200.0),
        &crate::render::FallbackFonts::default(),
        false,
        1.0,
    );
    let sheds: Vec<(usize, f32)> = plan.pair_spacing[0]
        .iter()
        .chain(extra.first().into_iter().flatten())
        .copied()
        .collect();
    let (text, from) =
        window_slice(&plan.texts[0], &plan.offsets.ranges[0], &window).unwrap_or(("", 0));
    let sheds = window_entries(&sheds, from, text.chars().count());
    builder.push_style(&style);
    add_horizontal_span(&mut builder, span, text, &sheds, &[], &style, fonts);
    let mut laid_out = LayoutParagraph::build(&mut builder);
    laid_out.layout(width);
    laid_out
}

/// Squeeze `text` under each line adjustment, at a width that fits the
/// first line up to the `nth` (from zero) `fits` character plus `extra_em`.
/// Returns the line adjustment, whether squeezing kept the next line's first
/// unit and whether the push-out-first gate expects a squeeze.
fn squeeze_outcomes(
    text: &str,
    (fits, nth): (char, usize),
    extra_em: f32,
) -> Vec<(LineAdjustment, bool, bool)> {
    use crate::shapes::text_vertical::test_support::{provider, EM, VPAL_TEST_FONT};
    init_state();
    let mut fonts = skia::textlayout::FontCollection::new();
    fonts.set_asset_font_manager(Some(provider(VPAL_TEST_FONT).into()));
    let span = TextSpan {
        font_size: EM,
        ..make_span(text, 0.0)
    };
    let paragraph = make_paragraph(vec![span], 0.0);
    let plan = HorizontalParagraphPlan::new(&paragraph);
    let chars: Vec<char> = plan.texts[0].chars().collect();
    let fits_at = chars
        .iter()
        .enumerate()
        .filter(|(_, ch)| **ch == fits)
        .nth(nth)
        .map(|(at, _)| at)
        .unwrap();
    let wide = squeeze_window_layout(&paragraph, &plan, &fonts, 1000.0, &[], FULL_WINDOW);
    let right = wide.get_rects_for_range(
        fits_at..fits_at + 1,
        RectHeightStyle::Tight,
        RectWidthStyle::Tight,
    )[0]
    .rect
    .right();
    let width = right + extra_em * EM;
    let natural = squeeze_window_layout(&paragraph, &plan, &fonts, width, &[], FULL_WINDOW);
    let natural_end = natural.get_line_metrics()[0].end_index;
    assert!(
        natural_end <= fits_at,
        "the unit at {fits:?} moves down whole"
    );
    let may_fail = super::justify::horizontal_push_out_may_fail(&paragraph, &plan);
    [
        LineAdjustment::PushInFirst,
        LineAdjustment::PushOutFirst,
        LineAdjustment::PushOutOnly,
    ]
    .into_iter()
    .map(|adjustment| {
        let lay_out = |extra: &[Vec<(usize, f32)>], window: std::ops::Range<usize>| {
            squeeze_window_layout(&paragraph, &plan, &fonts, width, extra, window)
        };
        let (sheds, squeezed) =
            horizontal_squeeze_sheds(&paragraph, &plan, width, adjustment, &lay_out);
        let kept = squeezed.get_line_metrics()[0].end_index > fits_at;
        assert_eq!(kept, !sheds[0].is_empty(), "{adjustment:?}");
        (adjustment, kept, may_fail)
    })
    .collect()
}

#[test]
fn push_out_first_squeezes_when_push_out_leaves_a_closing_bracket_at_the_line_start() {
    // Without kinsoku the line would break before the fifth 」. Moving that
    // break back to あ crosses five kinsoku pairs, more than oidashi moves
    // over, so push-out first squeezes the commas as push-in first does.
    let outcomes = squeeze_outcomes("あく、あく、あ」」」」」くあく", ('」', 3), 0.0);
    for (adjustment, kept, may_fail) in outcomes {
        assert!(may_fail);
        assert_eq!(
            kept,
            adjustment != LineAdjustment::PushOutOnly,
            "{adjustment:?}"
        );
    }
}

#[test]
fn push_out_first_keeps_push_out_when_it_resolves_the_line_start() {
    // Without kinsoku the line would break before 」; moving あ」 down
    // resolves it, so only push-in first squeezes.
    let outcomes = squeeze_outcomes("あく、あく、あ」くあく", ('あ', 2), 0.3);
    for (adjustment, kept, may_fail) in outcomes {
        assert!(!may_fail);
        assert_eq!(
            kept,
            adjustment == LineAdjustment::PushInFirst,
            "{adjustment:?}"
        );
    }
}
