use crate::ui::{Color, Guide, GuideKind, Rect};

pub struct GuidePool {
    horizontal: Vec<Guide>,
    vertical: Vec<Guide>,
}

impl GuidePool {
    pub fn new() -> Self {
        Self {
            horizontal: Vec::new(),
            vertical: Vec::new(),
        }
    }

    pub fn set(&mut self, guides: Vec<Guide>) {
        self.horizontal.clear();
        self.vertical.clear();

        for guide in guides {
            match guide.kind {
                GuideKind::Vertical(_) => self.vertical.push(guide),
                GuideKind::Horizontal(_) => self.horizontal.push(guide),
            }
        }

        self.horizontal
            .sort_by(|a, b| a.position().total_cmp(&b.position()));
        self.vertical
            .sort_by(|a, b| a.position().total_cmp(&b.position()));
    }

    pub fn find_at(&self, x: f32, y: f32, zoom: f32, tolerance: f32) -> Option<&Guide> {
        if zoom <= 0.0 || tolerance < 0.0 {
            return None;
        }

        let world_tolerance = tolerance / zoom;
        let vertical = Self::find_closest_in_axis(&self.vertical, x, world_tolerance);
        let horizontal = Self::find_closest_in_axis(&self.horizontal, y, world_tolerance);

        match (vertical, horizontal) {
            (Some(v), Some(h)) => {
                let v_dist = (v.position() - x).abs();
                let h_dist = (h.position() - y).abs();
                if v_dist <= h_dist {
                    Some(v)
                } else {
                    Some(h)
                }
            }
            (v, h) => v.or(h),
        }
    }

    fn find_closest_in_axis(guides: &[Guide], coord: f32, world_tolerance: f32) -> Option<&Guide> {
        if guides.is_empty() {
            return None;
        }

        // NOTE: `partition_point` is a binary search, so this is O(log n)
        let idx = guides.partition_point(|guide| guide.position() < coord);
        let mut closest: Option<&Guide> = None;
        let mut closest_dist = world_tolerance;

        for candidate_idx in [idx.wrapping_sub(1), idx] {
            if candidate_idx < guides.len() {
                let guide = &guides[candidate_idx];
                let dist = (guide.position() - coord).abs();
                if dist <= world_tolerance && dist <= closest_dist {
                    closest_dist = dist;
                    closest = Some(guide);
                }
            }
        }

        closest
    }
}

impl Default for GuidePool {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Copy)]
pub struct RulerState {
    pub visible: bool,
    // The rounded canvas frame/border. Drawn even when `visible` is false
    // (rulers toggled off), but hidden in hide-UI mode.
    pub frame: bool,
    pub offset_x: f32,
    pub offset_y: f32,
    pub selection: Option<Rect>,
    pub bg_color: Color,
    pub border_color: Color,
    pub label_color: Color,
    pub accent_color: Color,
}

impl Default for RulerState {
    fn default() -> Self {
        Self {
            visible: false,
            frame: true,
            offset_x: 0.0,
            offset_y: 0.0,
            selection: None,
            bg_color: Color::from_argb(0xff, 0x18, 0x18, 0x1a),
            border_color: Color::from_argb(0xff, 0x2e, 0x2e, 0x36),
            label_color: Color::from_argb(0xff, 0xb1, 0xb2, 0xb5),
            accent_color: Color::from_argb(0xff, 0x91, 0xff, 0x11),
        }
    }
}

impl RulerState {
    pub fn set_selection(&mut self, has: bool, x: f32, y: f32, w: f32, h: f32) {
        self.selection = if has {
            Some(Rect::from_xywh(x, y, w, h))
        } else {
            None
        };
    }
}

pub struct UIState {
    guides: GuidePool,
    rulers: RulerState,
    // TODO: show grid
}

impl UIState {
    pub fn new() -> Self {
        Self {
            guides: GuidePool::default(),
            rulers: RulerState::default(),
        }
    }

    pub fn guides(&self) -> (&Vec<Guide>, &Vec<Guide>) {
        (&self.guides.horizontal, &self.guides.vertical)
    }

    pub fn set_guides(&mut self, guides: Vec<Guide>) {
        self.guides.set(guides);
    }

    pub fn find_guide_at(&self, x: f32, y: f32, zoom: f32, tolerance: f32) -> Option<&Guide> {
        self.guides.find_at(x, y, zoom, tolerance)
    }

    pub fn ruler_state(&self) -> &RulerState {
        &self.rulers
    }

    pub fn ruler_state_mut(&mut self) -> &mut RulerState {
        &mut self.rulers
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shapes::Color;

    fn vertical_guide(position: f32, index: usize) -> Guide {
        Guide::new(
            GuideKind::Vertical(position),
            Color::BLACK,
            Some(index),
            None,
        )
    }

    fn horizontal_guide(position: f32, index: usize) -> Guide {
        Guide::new(
            GuideKind::Horizontal(position),
            Color::BLACK,
            Some(index),
            None,
        )
    }

    fn pool_with(guides: Vec<Guide>) -> GuidePool {
        let mut pool = GuidePool::new();
        pool.set(guides);
        pool
    }

    #[test]
    fn set_replaces_existing_guides() {
        let mut pool = pool_with(vec![vertical_guide(100.0, 0)]);
        pool.set(vec![vertical_guide(200.0, 0)]);

        assert_eq!(pool.vertical.len(), 1);
        assert_eq!(pool.vertical[0].kind, GuideKind::Vertical(200.0));
        assert!(pool.horizontal.is_empty());
    }

    #[test]
    fn set_drops_removed_guides() {
        let mut pool = pool_with(vec![
            vertical_guide(100.0, 0),
            vertical_guide(200.0, 1),
            horizontal_guide(300.0, 2),
        ]);
        pool.set(vec![vertical_guide(100.0, 0), horizontal_guide(300.0, 1)]);

        assert_eq!(pool.vertical.len(), 1);
        assert_eq!(pool.horizontal.len(), 1);
        assert_eq!(pool.vertical[0].kind, GuideKind::Vertical(100.0));
        assert_eq!(pool.horizontal[0].kind, GuideKind::Horizontal(300.0));
    }

    #[test]
    fn find_at_returns_none_when_no_guides() {
        let pool = GuidePool::new();
        assert!(pool.find_at(100.0, 100.0, 1.0, 8.0).is_none());
    }

    #[test]
    fn find_at_finds_vertical_guide_within_tolerance() {
        let pool = pool_with(vec![vertical_guide(100.0, 0)]);
        let guide = pool.find_at(102.0, 50.0, 1.0, 8.0).unwrap();
        assert_eq!(guide.index, 0);
        assert_eq!(guide.kind, GuideKind::Vertical(100.0));
    }

    #[test]
    fn find_at_misses_vertical_guide_outside_tolerance() {
        let pool = pool_with(vec![vertical_guide(100.0, 0)]);
        assert!(pool.find_at(110.0, 50.0, 1.0, 8.0).is_none());
    }

    #[test]
    fn find_at_finds_horizontal_guide_within_tolerance() {
        let pool = pool_with(vec![horizontal_guide(200.0, 1)]);
        let guide = pool.find_at(50.0, 203.0, 1.0, 8.0).unwrap();
        assert_eq!(guide.index, 1);
        assert_eq!(guide.kind, GuideKind::Horizontal(200.0));
    }

    #[test]
    fn find_at_picks_closest_vertical_guide() {
        let pool = pool_with(vec![vertical_guide(100.0, 0), vertical_guide(105.0, 1)]);
        let guide = pool.find_at(103.0, 0.0, 1.0, 8.0).unwrap();
        assert_eq!(guide.index, 1);
    }

    #[test]
    fn find_at_prefers_closer_guide_at_intersection() {
        let pool = pool_with(vec![vertical_guide(102.0, 0), horizontal_guide(100.0, 1)]);
        let guide = pool.find_at(100.0, 100.0, 1.0, 8.0).unwrap();
        assert_eq!(guide.index, 1);
        assert_eq!(guide.kind, GuideKind::Horizontal(100.0));
    }

    #[test]
    fn find_at_scales_tolerance_with_zoom() {
        let pool = pool_with(vec![vertical_guide(100.0, 0)]);

        assert!(pool.find_at(104.0, 0.0, 2.0, 8.0).is_some());
        assert!(pool.find_at(105.0, 0.0, 2.0, 8.0).is_none());
    }
}
