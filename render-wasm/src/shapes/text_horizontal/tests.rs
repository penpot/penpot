use super::justify::{horizontal_justify_spacing, horizontal_squeeze_sheds};
use super::ruby::{
    horizontal_ruby_paint, horizontal_ruby_spacing_with, horizontal_ruby_targets, ruby_glyph_refs,
    split_counts_by_extent, RubyGlyphRef, RubyLine,
};
use super::*;
use crate::shapes::{TextAlign, TextDirection, TextTransform};
use crate::Uuid;

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
    Paragraph::new(
        TextAlign::default(),
        TextDirection::LTR,
        None,
        None,
        1.0,
        letter_spacing,
        spans,
    )
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
        let mut laid_out = builder.build();
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
            kinsoku::WESTERN_WORD_SPACE,
            kinsoku::WESTERN_WORD_SPACE
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
        &["sourcesanspro".to_string()],
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
fn horizontal_plans_are_cached_until_the_content_changes() {
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
fn horizontal_annotation_uses_the_base_em_not_typographic_leading() {
    let rect = skia::Rect::from_xywh(0.0, 66.0, 56.0, 90.0);

    assert_eq!(horizontal_annotation_over_top(rect, 56.0), 88.0);
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
    let mut laid_out = builder.build();
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

    let mut laid_out = builder.build();
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
fn horizontal_aki_sheds_follow_punctuation_pairs() {
    init_state();
    let texts = vec!["あ。」い、".to_string(), "「「う".to_string()];
    let paragraph = make_paragraph(texts.iter().map(|text| make_span(text, 0.0)).collect(), 0.0);
    assert_eq!(
        horizontal_aki_sheds(&paragraph, &texts),
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
        horizontal_aki_sheds(&paragraph, &texts),
        vec![vec![(0, 16.0)], vec![]],
        "the 16px 「 gives up half of the next 32px bracket's em"
    );
}

#[test]
fn horizontal_aki_sheds_skip_curly_quotes() {
    init_state();
    for text in ["“‘Hi’”", "あ“い”。", "あ‘い’、"] {
        let texts = vec![text.to_string()];
        let paragraph = make_paragraph(vec![make_span(text, 0.0)], 0.0);
        assert_eq!(
            horizontal_aki_sheds(&paragraph, &texts),
            vec![Vec::<(usize, f32)>::new()],
            "no aki shed in {text:?}"
        );
    }
}

#[test]
fn horizontal_aki_sheds_skip_word_joiners_and_palt() {
    init_state();
    let joined = vec!["。\u{2060}」".to_string()];
    let paragraph = make_paragraph(vec![make_span(&joined[0], 0.0)], 0.0);
    assert_eq!(
        horizontal_aki_sheds(&paragraph, &joined),
        vec![vec![(0, 8.0)]]
    );

    let mut palt = make_span("。」", 0.0);
    palt.font_features = FontFeatures::Palt;
    let paragraph = make_paragraph(vec![palt], 0.0);
    assert_eq!(
        horizontal_aki_sheds(&paragraph, &["。」".to_string()]),
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
    let mut laid_out = builder.build();
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
        .filter(|entry| entry.direction == super::super::text_vertical::DIRECTION_EMPHASIS_MARK)
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
    };
    let plain = make_span("漢", 0.0);
    let mut stacked = make_span("漢", 0.0);
    stacked.ruby = "かん".to_string();
    stacked.annotation_clearance = AnnotationClearance::Auto;

    let plain_box = horizontal_emphasis_mark_box(&plain, &placement);
    let stacked_box = horizontal_emphasis_mark_box(&stacked, &placement);

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
    let mut laid_out = builder.build();
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
    let mut laid_out = builder.build();
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
            &plan.sheds[0],
            extra,
            &style,
            &fonts,
        );
        let mut laid_out = builder.build();
        laid_out.layout(width);
        laid_out
    };

    let natural = lay_out(&[]);
    let spacing = horizontal_justify_spacing(&natural, &plan, width);
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
        let mut probe = builder.build();
        probe.layout(1000.0);
        probe.max_intrinsic_width()
    };
    // Seven characters fit; the eighth overflows by less than the 、 aki.
    let width = advance * 7.6;
    let lay_out = |extra: &[Vec<(usize, f32)>]| {
        let sheds: Vec<(usize, f32)> = plan.sheds[0]
            .iter()
            .chain(extra.first().into_iter().flatten())
            .copied()
            .collect();
        let mut builder = ParagraphBuilder::new(&paragraph_style, &fonts);
        builder.push_style(&style);
        add_horizontal_span(
            &mut builder,
            &paragraph.children()[0],
            &plan.texts[0],
            &sheds,
            &[],
            &style,
            &fonts,
        );
        let mut laid_out = builder.build();
        laid_out.layout(width);
        laid_out
    };

    // Line ends count builder UTF-16 units, word joiners included.
    let builder_text: Vec<char> = plan.texts[0].chars().collect();
    let natural = lay_out(&[]);
    let natural_end = natural.get_line_metrics()[0].end_index;
    assert_eq!(
        builder_text[natural_end], 'か',
        "the natural line ends before か"
    );
    let (sheds, squeezed) = horizontal_squeeze_sheds(&paragraph, &plan, width, &lay_out);

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
