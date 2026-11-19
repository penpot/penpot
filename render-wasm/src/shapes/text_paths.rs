use crate::render::text::{decoration_segments, list_markers};
use crate::shapes::text::{vertical_align_offset, Paragraph, TextContent};
use crate::shapes::VerticalAlign;
use skia_safe::{
    self as skia,
    textlayout::{paragraph::VisitorInfo, Paragraph as SkiaParagraph},
    Point,
};
use std::ops::Deref;

pub struct TextPaths(TextContent);

impl TextPaths {
    pub fn new(text_content: TextContent) -> Self {
        Self(text_content)
    }

    pub fn get_paths(&self, vertical_align: VerticalAlign) -> Vec<skia::Path> {
        let layout_width = self.0.get_width(self.bounds.width());
        let mut paragraph_builders = self.0.paragraph_builder_group_from_text(None);
        let mut paragraphs: Vec<SkiaParagraph> = paragraph_builders
            .iter_mut()
            .filter_map(|group| group.first_mut())
            .map(|paragraph_builder| {
                let mut paragraph = paragraph_builder.build();
                paragraph.layout(layout_width);
                paragraph
            })
            .collect();

        let total_height: f32 = paragraphs.iter().map(|p| p.height()).sum();
        let top = self.bounds.y()
            + vertical_align_offset(self.bounds.height(), total_height, vertical_align);

        let mut paths: Vec<skia::Path> = list_markers(
            paragraphs.iter().map(Some),
            self.0.paragraphs(),
            self.bounds.x(),
            top,
            layout_width,
        )
        .into_iter()
        .map(|marker| {
            let mut builder = skia::PathBuilder::new();
            builder.add_circle(marker.center, marker.radius, None);
            builder.detach()
        })
        .collect();

        let mut offset_y = top;
        for (paragraph, text_paragraph) in paragraphs.iter_mut().zip(self.0.paragraphs()) {
            let origin = Point::new(self.bounds.x(), offset_y);
            Self::collect_paragraph_paths(paragraph, text_paragraph, origin, &mut paths);
            offset_y += paragraph.height();
        }

        paths
    }

    fn collect_paragraph_paths(
        paragraph: &mut SkiaParagraph,
        text_paragraph: &Paragraph,
        origin: Point,
        paths: &mut Vec<skia::Path>,
    ) {
        for deco in decoration_segments(paragraph, text_paragraph, origin.x, origin.y) {
            let mut builder = skia::PathBuilder::new();
            builder.add_rect(deco.rect(), None, None);
            paths.push(builder.detach());
        }

        paragraph.visit(|_: usize, info: Option<&VisitorInfo>| {
            let Some(info) = info else {
                return;
            };

            let font = info.font();
            let run_origin = origin + info.origin();
            let mut builder = skia::PathBuilder::new();
            let mut has_glyphs = false;

            for (glyph, position) in info.glyphs().iter().zip(info.positions().iter()) {
                let Some(glyph_path) = font.get_path(*glyph) else {
                    continue;
                };
                builder.add_path(&glyph_path.with_offset(run_origin + *position), None);
                has_glyphs = true;
            }

            if has_glyphs {
                paths.push(builder.detach());
            }
        });
    }
}

impl Deref for TextPaths {
    type Target = TextContent;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::globals::TestRenderResourcesGuard;
    use crate::math::Rect;
    use crate::render::RenderResources;
    use crate::shapes::{
        FontFamily, FontStyle, GrowType, ListStyleType, TextAlign, TextDirection, TextSpan,
    };
    use crate::uuid::Uuid;

    /// Paths of a 200x100 text at the origin, one 14px paragraph per entry.
    fn paths_of_paragraphs(
        paragraphs: Vec<(&str, TextDirection, ListStyleType)>,
    ) -> Vec<skia::Path> {
        let mut content =
            TextContent::new(Rect::from_xywh(0.0, 0.0, 200.0, 100.0), GrowType::Fixed);
        for (text, direction, list_style_type) in paragraphs {
            let span = TextSpan::new(
                text.to_string(),
                FontFamily::new(Uuid::nil(), 400, FontStyle::Normal),
                14.0,
                1.2,
                0.0,
                None,
                None,
                direction,
                400,
                Uuid::nil(),
                vec![],
            );
            content.add_paragraph(
                Paragraph::new(TextAlign::Left, direction, None, None, 1.2, 0.0, vec![span])
                    .with_list_style_type(list_style_type),
            );
        }
        TextPaths::new(content).get_paths(VerticalAlign::Top)
    }

    fn paths_of(list_style_type: ListStyleType) -> Vec<skia::Path> {
        paths_of_paragraphs(vec![("hello", TextDirection::LTR, list_style_type)])
    }

    /// Bounds of the paths that lie wholly outside the 200px-wide text box.
    fn bullet_bounds(paths: Vec<skia::Path>) -> Vec<Rect> {
        paths
            .into_iter()
            .map(|path| *path.bounds())
            .filter(|bounds| bounds.right() < 0.0 || bounds.left() > 200.0)
            .collect()
    }

    #[test]
    fn list_paragraph_paths_include_the_bullet() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        // Font size 14: the bullet is centered at x = -6.86 with radius 2.24.
        let bullet = paths_of(ListStyleType::Disc)
            .into_iter()
            .map(|path| *path.bounds())
            .find(|bounds| bounds.right() < 0.0)
            .expect("a path before the text box");
        assert!((bullet.center_x() + 6.86).abs() < 0.01, "{bullet:?}");
        assert!((bullet.width() - 4.48).abs() < 0.01, "{bullet:?}");
    }

    #[test]
    fn empty_list_item_paths_include_its_bullet() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let bullets = bullet_bounds(paths_of_paragraphs(vec![
            ("hello", TextDirection::LTR, ListStyleType::Disc),
            ("", TextDirection::LTR, ListStyleType::Disc),
        ]));
        assert_eq!(bullets.len(), 2, "{bullets:?}");
    }

    #[test]
    fn rtl_list_paragraph_paths_put_the_bullet_after_the_text_box() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        let bullets = bullet_bounds(paths_of_paragraphs(vec![(
            "hello",
            TextDirection::RTL,
            ListStyleType::Disc,
        )]));
        assert_eq!(bullets.len(), 1, "{bullets:?}");
        // Font size 14: radius 2.24 plus a 4.62 gap past the right edge.
        assert!(
            (bullets[0].center_x() - (200.0 + 6.86)).abs() < 0.01,
            "{bullets:?}"
        );
    }

    #[test]
    fn plain_paragraph_paths_have_no_bullet() {
        let mut resources = RenderResources::try_new_headless().expect("headless resources");
        let _guard = TestRenderResourcesGuard::install(&mut resources);
        assert!(paths_of(ListStyleType::None)
            .iter()
            .all(|path| path.bounds().left() >= 0.0));
    }
}
