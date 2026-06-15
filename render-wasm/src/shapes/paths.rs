use crate::math;
use crate::shapes::svg_attrs::{FillRule, SvgAttrs};
use skia_safe::{self as skia, Matrix};

mod subpaths;

type Point = (f32, f32);

#[derive(Debug, PartialEq, Copy, Clone)]
pub enum Segment {
    MoveTo(Point),
    LineTo(Point),
    CurveTo((Point, Point, Point)),
    Close,
}

impl Segment {}

#[derive(Debug, Clone, PartialEq)]
pub struct Path {
    segments: Vec<Segment>,
    skia_path: skia::Path,
    open: bool,
}

impl Default for Path {
    fn default() -> Self {
        Self::new(vec![])
    }
}

impl Path {
    pub fn new(segments: Vec<Segment>) -> Self {
        let mut pb = skia::PathBuilder::new();

        // Don't auto-close the Skia path when start ≈ end.
        // SVG treats these as open paths (caps apply at endpoints).
        // Auto-closing changes stroke behavior from caps to joins,
        // producing artifacts at self-intersection points.
        // Only explicit Segment::Close should close the Skia path.
        for segment in segments.iter() {
            match *segment {
                Segment::MoveTo(xy) => {
                    pb.move_to(xy);
                }
                Segment::LineTo(xy) => {
                    pb.line_to(xy);
                }
                Segment::CurveTo((c1, c2, xy)) => {
                    pb.cubic_to(c1, c2, xy);
                }
                Segment::Close => {
                    pb.close();
                }
            }
        }

        let skia_path = pb.detach();
        let open = subpaths::is_open_path(&segments);

        Self {
            segments,
            skia_path,
            open,
        }
    }

    pub fn from_skia_path(path: skia::Path) -> Self {
        let verbs = path.verbs();
        let points = path.points();
        let fill_type = path.fill_type();

        let mut segments = Vec::new();
        let mut current_point = 0;
        let mut last_point = skia::Point::new(0.0, 0.0);

        for verb in verbs {
            match verb {
                skia::PathVerb::Move => {
                    let p = points[current_point];
                    segments.push(Segment::MoveTo((p.x, p.y)));
                    last_point = p;
                    current_point += 1;
                }
                skia::PathVerb::Line => {
                    let p = points[current_point];
                    segments.push(Segment::LineTo((p.x, p.y)));
                    last_point = p;
                    current_point += 1;
                }
                skia::PathVerb::Quad => {
                    // Elevate quadratic to cubic: CP1 = P0 + 2/3*(Pctrl-P0), CP2 = P2 + 2/3*(Pctrl-P2)
                    let ctrl = points[current_point];
                    let end = points[current_point + 1];
                    let cp1x = last_point.x + (2.0 / 3.0) * (ctrl.x - last_point.x);
                    let cp1y = last_point.y + (2.0 / 3.0) * (ctrl.y - last_point.y);
                    let cp2x = end.x + (2.0 / 3.0) * (ctrl.x - end.x);
                    let cp2y = end.y + (2.0 / 3.0) * (ctrl.y - end.y);
                    segments.push(Segment::CurveTo((
                        (cp1x, cp1y),
                        (cp2x, cp2y),
                        (end.x, end.y),
                    )));
                    last_point = end;
                    current_point += 2;
                }
                skia::PathVerb::Conic => {
                    // Approximate conic (rational quadratic) as cubic via degree elevation.
                    // This ignores the conic weight and treats it as a regular quadratic —
                    // accurate enough for the typical w≈1 font glyphs that use this path.
                    // For higher-fidelity conversion use from_skia_path_accurate instead.
                    let ctrl = points[current_point];
                    let end = points[current_point + 1];
                    let cp1x = last_point.x + (2.0 / 3.0) * (ctrl.x - last_point.x);
                    let cp1y = last_point.y + (2.0 / 3.0) * (ctrl.y - last_point.y);
                    let cp2x = end.x + (2.0 / 3.0) * (ctrl.x - end.x);
                    let cp2y = end.y + (2.0 / 3.0) * (ctrl.y - end.y);
                    segments.push(Segment::CurveTo((
                        (cp1x, cp1y),
                        (cp2x, cp2y),
                        (end.x, end.y),
                    )));
                    last_point = end;
                    current_point += 2;
                }
                skia::PathVerb::Cubic => {
                    let p1 = points[current_point];
                    let p2 = points[current_point + 1];
                    let p3 = points[current_point + 2];
                    segments.push(Segment::CurveTo(((p1.x, p1.y), (p2.x, p2.y), (p3.x, p3.y))));
                    last_point = p3;
                    current_point += 3;
                }
                skia::PathVerb::Close => {
                    segments.push(Segment::Close);
                }
            }
        }

        let mut result = Path::new(segments);
        result.skia_path.set_fill_type(fill_type);
        result
    }

    /// Like `from_skia_path` but properly converts conics to cubic beziers
    /// (using Skia's conic-to-quad + quad-to-cubic elevation). Use this when
    /// accurate curve conversion matters (e.g. stroke-to-path on circles,
    /// text glyph paths which contain many conic segments).
    pub fn from_skia_path_accurate(path: skia::Path) -> Self {
        let verbs = path.verbs();
        let points = path.points();
        let conic_weights = path.conic_weights();
        let fill_type = path.fill_type();

        let mut segments = Vec::new();
        let mut current_point = 0;
        let mut current_conic = 0;
        let mut last_point = skia::Point::new(0.0, 0.0);
        let mut subpath_start = skia::Point::new(0.0, 0.0);

        for verb in verbs {
            match verb {
                skia::PathVerb::Move => {
                    let p = points[current_point];
                    segments.push(Segment::MoveTo((p.x, p.y)));
                    last_point = p;
                    subpath_start = p;
                    current_point += 1;
                }
                skia::PathVerb::Line => {
                    let p = points[current_point];
                    if p != last_point {
                        segments.push(Segment::LineTo((p.x, p.y)));
                        last_point = p;
                    }
                    current_point += 1;
                }
                skia::PathVerb::Quad => {
                    let ctrl = points[current_point];
                    let end = points[current_point + 1];
                    let cp1x = last_point.x + (2.0 / 3.0) * (ctrl.x - last_point.x);
                    let cp1y = last_point.y + (2.0 / 3.0) * (ctrl.y - last_point.y);
                    let cp2x = end.x + (2.0 / 3.0) * (ctrl.x - end.x);
                    let cp2y = end.y + (2.0 / 3.0) * (ctrl.y - end.y);
                    segments.push(Segment::CurveTo((
                        (cp1x, cp1y),
                        (cp2x, cp2y),
                        (end.x, end.y),
                    )));
                    last_point = end;
                    current_point += 2;
                }
                skia::PathVerb::Conic => {
                    let ctrl = points[current_point];
                    let end = points[current_point + 1];
                    let w = conic_weights[current_conic];
                    current_conic += 1;

                    // pow2=0: 1 quad per conic. A circle (4 conics) becomes
                    // 4 cubics, matching the standard bezier approximation.
                    const POW2: usize = 0;
                    let quad_count = 1 << POW2;
                    let pts_count = 1 + 2 * quad_count;
                    let mut quad_pts = vec![skia::Point::default(); pts_count];
                    if skia::Path::convert_conic_to_quads(
                        last_point,
                        ctrl,
                        end,
                        w,
                        &mut quad_pts,
                        POW2,
                    )
                    .is_some()
                    {
                        let mut qp = last_point;
                        for i in 0..quad_count {
                            let qctrl = quad_pts[1 + i * 2];
                            let qend = quad_pts[2 + i * 2];
                            let cp1x = qp.x + (2.0 / 3.0) * (qctrl.x - qp.x);
                            let cp1y = qp.y + (2.0 / 3.0) * (qctrl.y - qp.y);
                            let cp2x = qend.x + (2.0 / 3.0) * (qctrl.x - qend.x);
                            let cp2y = qend.y + (2.0 / 3.0) * (qctrl.y - qend.y);
                            segments.push(Segment::CurveTo((
                                (cp1x, cp1y),
                                (cp2x, cp2y),
                                (qend.x, qend.y),
                            )));
                            qp = qend;
                        }
                        last_point = qp;
                    } else {
                        segments.push(Segment::LineTo((end.x, end.y)));
                        last_point = end;
                    }
                    current_point += 2;
                }
                skia::PathVerb::Cubic => {
                    let p1 = points[current_point];
                    let p2 = points[current_point + 1];
                    let p3 = points[current_point + 2];
                    segments.push(Segment::CurveTo(((p1.x, p1.y), (p2.x, p2.y), (p3.x, p3.y))));
                    last_point = p3;
                    current_point += 3;
                }
                skia::PathVerb::Close => {
                    if let Some(Segment::LineTo(p)) = segments.last() {
                        if (p.0 - subpath_start.x).abs() < 1e-5
                            && (p.1 - subpath_start.y).abs() < 1e-5
                        {
                            segments.pop();
                        }
                    }
                    segments.push(Segment::Close);
                }
            }
        }

        let mut result = Path::new(segments);
        result.skia_path.set_fill_type(fill_type);
        result
    }

    pub fn to_skia_path(&self, svg_attrs: Option<&SvgAttrs>) -> skia::Path {
        let mut path = self.skia_path.snapshot();
        if let Some(attrs) = svg_attrs {
            if attrs.fill_rule == FillRule::Evenodd {
                path.set_fill_type(skia::PathFillType::EvenOdd);
            }
        }
        path
    }

    pub fn contains(&self, p: skia::Point) -> bool {
        self.skia_path.contains(p)
    }

    pub fn is_even_odd(&self) -> bool {
        self.skia_path.fill_type() == skia::PathFillType::EvenOdd
    }

    // Builder method: set even-odd fill on this path and return it.
    // Use as `Path::new(segments).with_even_odd(is_even_odd)`.
    pub fn with_even_odd(mut self, is_even_odd: bool) -> Self {
        if is_even_odd {
            self.skia_path.set_fill_type(skia::PathFillType::EvenOdd);
        }
        self
    }

    pub fn is_open(&self) -> bool {
        self.open
    }

    pub fn transform(&mut self, mtx: &Matrix) {
        if math::is_move_only_matrix(mtx) {
            let tx = mtx.translate_x();
            let ty = mtx.translate_y();
            self.segments.iter_mut().for_each(|s| match s {
                Segment::MoveTo(p) | Segment::LineTo(p) => {
                    p.0 += tx;
                    p.1 += ty;
                }
                Segment::CurveTo((c1, c2, p)) => {
                    c1.0 += tx;
                    c1.1 += ty;
                    c2.0 += tx;
                    c2.1 += ty;
                    p.0 += tx;
                    p.1 += ty;
                }
                _ => {}
            });
            self.skia_path = self.skia_path.with_offset((tx, ty));
            return;
        }

        self.segments.iter_mut().for_each(|s| match s {
            Segment::MoveTo(p) => {
                let np = mtx.map_point(skia::Point::new(p.0, p.1));
                p.0 = np.x;
                p.1 = np.y;
            }
            Segment::LineTo(p) => {
                let np = mtx.map_point(skia::Point::new(p.0, p.1));
                p.0 = np.x;
                p.1 = np.y;
            }
            Segment::CurveTo((c1, c2, p)) => {
                let nc1 = mtx.map_point(skia::Point::new(c1.0, c1.1));
                c1.0 = nc1.x;
                c1.1 = nc1.y;

                let nc2 = mtx.map_point(skia::Point::new(c2.0, c2.1));
                c2.0 = nc2.x;
                c2.1 = nc2.y;

                let np = mtx.map_point(skia::Point::new(p.0, p.1));
                p.0 = np.x;
                p.1 = np.y;
            }
            _ => {}
        });

        self.skia_path = self.skia_path.make_transform(mtx);
    }

    pub fn segments(&self) -> &Vec<Segment> {
        &self.segments
    }

    pub fn bounds(&self) -> math::Bounds {
        math::Bounds::from_rect(self.skia_path.bounds())
    }
}
