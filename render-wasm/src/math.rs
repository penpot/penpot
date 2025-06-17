use skia_safe as skia;

pub type Rect = skia::Rect;
pub type Matrix = skia::Matrix;
pub type Vector = skia::Vector;
pub type Point = skia::Point;

const THRESHOLD: f32 = 0.001;

pub trait VectorExt {
    fn new_points(a: &Point, b: &Point) -> Vector;
}

impl VectorExt for Vector {
    // Creates a vector from two points
    fn new_points(from: &Point, to: &Point) -> Vector {
        Vector::new(to.x - from.x, to.y - from.y)
    }
}

pub fn is_close_to(current: f32, value: f32) -> bool {
    (current - value).abs() <= THRESHOLD
}

pub fn identitish(m: Matrix) -> bool {
    is_close_to(m.scale_x(), 1.0)
        && is_close_to(m.scale_y(), 1.0)
        && is_close_to(m.translate_x(), 0.0)
        && is_close_to(m.translate_y(), 0.0)
        && is_close_to(m.skew_x(), 0.0)
        && is_close_to(m.skew_y(), 0.0)
}

#[derive(Debug, Copy, Clone, PartialEq)]
pub struct Bounds {
    pub nw: Point,
    pub ne: Point,
    pub se: Point,
    pub sw: Point,
}

fn vec_min_max(arr: &Vec<Option<f32>>) -> Option<(f32, f32)> {
    let mut minv: Option<f32> = None;
    let mut maxv: Option<f32> = None;

    for it in arr {
        if let Some(it) = *it {
            match minv {
                None => minv = Some(it),
                Some(n) => minv = Some(f32::min(it, n)),
            }
            match maxv {
                None => maxv = Some(it),
                Some(n) => maxv = Some(f32::max(it, n)),
            }
        }
    }

    Some((minv?, maxv?))
}

impl Bounds {
    pub fn new(nw: Point, ne: Point, se: Point, sw: Point) -> Self {
        Self { nw, ne, se, sw }
    }

    pub fn join_bounds(bounds: &[Bounds]) -> Self {
        let (min_x, min_y, max_x, max_y) =
            bounds
                .iter()
                .fold((f32::MAX, f32::MAX, f32::MIN, f32::MIN), {
                    |(min_x, min_y, max_x, max_y), bound| {
                        (
                            f32::min(bound.min_x(), min_x),
                            f32::min(bound.min_y(), min_y),
                            f32::max(bound.max_x(), max_x),
                            f32::max(bound.max_y(), max_y),
                        )
                    }
                });

        Self::new(
            Point::new(min_x, min_y),
            Point::new(max_x, min_y),
            Point::new(max_x, max_y),
            Point::new(min_x, max_y),
        )
    }

    pub fn horizontal_vec(&self) -> Vector {
        Vector::new_points(&self.nw, &self.ne)
    }

    pub fn vertical_vec(&self) -> Vector {
        Vector::new_points(&self.nw, &self.sw)
    }

    pub fn hv(&self, scalar: f32) -> Vector {
        let mut hv = self.horizontal_vec();
        hv.normalize();
        hv.scale(scalar);
        hv
    }

    pub fn vv(&self, scalar: f32) -> Vector {
        let mut vv = self.vertical_vec();
        vv.normalize();
        vv.scale(scalar);
        vv
    }

    pub fn width(&self) -> f32 {
        Point::distance(self.nw, self.ne)
    }

    pub fn height(&self) -> f32 {
        Point::distance(self.nw, self.sw)
    }

    pub fn transform(&self, mtx: &Matrix) -> Self {
        Self {
            nw: mtx.map_point(self.nw),
            ne: mtx.map_point(self.ne),
            se: mtx.map_point(self.se),
            sw: mtx.map_point(self.sw),
        }
    }

    pub fn transform_mut(&mut self, mtx: &Matrix) {
        self.nw = mtx.map_point(self.nw);
        self.ne = mtx.map_point(self.ne);
        self.se = mtx.map_point(self.se);
        self.sw = mtx.map_point(self.sw);
    }

    // FIXME: this looks like this should be a try_from static method or similar
    pub fn box_bounds(&self, other: &Self) -> Option<Self> {
        self.with_points(other.points())
    }

    // FIXME: this looks like this should be a try_from static method or similar
    pub fn with_points(&self, points: Vec<Point>) -> Option<Self> {
        let hv = self.horizontal_vec();
        let vv = self.vertical_vec();

        let hr = Ray::new(self.nw, hv);
        let vr = Ray::new(self.nw, vv);

        let (min_ht, max_ht) = vec_min_max(
            &points
                .iter()
                .map(|p| intersect_rays_t(&hr, &Ray::new(*p, vv)))
                .collect(),
        )?;

        let (min_vt, max_vt) = vec_min_max(
            &points
                .iter()
                .map(|p| intersect_rays_t(&vr, &Ray::new(*p, hv)))
                .collect(),
        )?;

        let nw = intersect_rays(&Ray::new(hr.t(min_ht), vv), &Ray::new(vr.t(min_vt), hv))?;
        let ne = intersect_rays(&Ray::new(hr.t(max_ht), vv), &Ray::new(vr.t(min_vt), hv))?;
        let sw = intersect_rays(&Ray::new(hr.t(min_ht), vv), &Ray::new(vr.t(max_vt), hv))?;
        let se = intersect_rays(&Ray::new(hr.t(max_ht), vv), &Ray::new(vr.t(max_vt), hv))?;

        Some(Self { nw, ne, se, sw })
    }

    pub fn points(&self) -> Vec<Point> {
        vec![self.nw, self.ne, self.se, self.sw]
    }

    pub fn left(&self, p: Point) -> f32 {
        let hr = Ray::new(p, self.horizontal_vec());
        let vr = Ray::new(self.nw, self.vertical_vec());

        let mut result = if let Some(project_point) = intersect_rays(&hr, &vr) {
            Point::distance(project_point, p)
        } else {
            0.0
        };

        if vr.is_positive_side(&p) {
            result = -result;
        }

        if self.flip_y() {
            result = -result;
        }

        if self.flip_x() {
            result = -result;
        }

        result
    }

    pub fn right(&self, p: Point) -> f32 {
        let hr = Ray::new(p, self.horizontal_vec());
        let vr = Ray::new(self.ne, self.vertical_vec());

        let mut result = if let Some(project_point) = intersect_rays(&hr, &vr) {
            Point::distance(project_point, p)
        } else {
            0.0
        };

        if !vr.is_positive_side(&p) {
            result = -result;
        }

        if self.flip_y() {
            result = -result;
        }

        if self.flip_x() {
            result = -result;
        }

        result
    }

    pub fn top(&self, p: Point) -> f32 {
        let vr = Ray::new(p, self.vertical_vec());
        let hr = Ray::new(self.nw, self.horizontal_vec());

        let mut result = if let Some(project_point) = intersect_rays(&vr, &hr) {
            Point::distance(project_point, p)
        } else {
            0.0
        };

        if !hr.is_positive_side(&p) {
            result = -result;
        }

        if self.flip_y() {
            result = -result;
        }

        if self.flip_x() {
            result = -result;
        }

        result
    }

    pub fn bottom(&self, p: Point) -> f32 {
        let vr = Ray::new(p, self.vertical_vec());
        let hr = Ray::new(self.sw, self.horizontal_vec());

        let mut result = if let Some(project_point) = intersect_rays(&vr, &hr) {
            Point::distance(project_point, p)
        } else {
            0.0
        };

        if hr.is_positive_side(&p) {
            result = -result;
        }

        if self.flip_y() {
            result = -result;
        }

        if self.flip_x() {
            result = -result;
        }

        result
    }

    pub fn center(&self) -> Point {
        // Calculates the centroid of the four points
        Point::new(
            self.nw.x + (self.se.x - self.nw.x) / 2.0,
            self.nw.y + (self.se.y - self.nw.y) / 2.0,
        )
    }

    pub fn transform_matrix(&self) -> Option<Matrix> {
        let w2 = self.width() / 2.0;
        let h2 = self.height() / 2.0;

        let s1x = -w2;
        let s1y = -h2;
        let s2x = w2;
        let s2y = -h2;
        let s4x = -w2;
        let s4y = h2;

        let d1x = self.nw.x;
        let d1y = self.nw.y;
        let d2x = self.ne.x;
        let d2y = self.ne.y;
        let d4x = self.sw.x;
        let d4y = self.sw.y;

        // TODO: Check how fast is to calculate here the invert matrix
        let mut target_points_matrix = Matrix::new_all(d1x, d2x, d4x, d1y, d2y, d4y, 1.0, 1.0, 1.0);
        let source_points_matrix = Matrix::new_all(s1x, s2x, s4x, s1y, s2y, s4y, 1.0, 1.0, 1.0);

        let source_points_matrix_inv = source_points_matrix.invert()?;
        target_points_matrix.pre_concat(&source_points_matrix_inv);

        // Ignore translations
        target_points_matrix.set_translate_x(0.0);
        target_points_matrix.set_translate_y(0.0);

        Some(target_points_matrix)
    }

    // TODO: Probably we can improve performance here removing the access
    pub fn flip_x(&self) -> bool {
        let m = self.transform_matrix().unwrap_or_default();
        m.scale_x() < 0.0
    }

    // TODO: Probably we can improve performance here removing the access
    pub fn flip_y(&self) -> bool {
        let m = self.transform_matrix().unwrap_or_default();
        m.scale_y() < 0.0
    }

    pub fn to_rect(self) -> Rect {
        Rect::from_ltrb(self.min_x(), self.min_y(), self.max_x(), self.max_y())
    }

    pub fn min_x(&self) -> f32 {
        self.nw.x.min(self.ne.x).min(self.sw.x).min(self.se.x)
    }

    pub fn min_y(&self) -> f32 {
        self.nw.y.min(self.ne.y).min(self.sw.y).min(self.se.y)
    }

    pub fn max_x(&self) -> f32 {
        self.nw.x.max(self.ne.x).max(self.sw.x).max(self.se.x)
    }

    pub fn max_y(&self) -> f32 {
        self.nw.y.max(self.ne.y).max(self.sw.y).max(self.se.y)
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Ray {
    origin: Point,
    direction: Vector,
}

impl Ray {
    pub fn new(origin: Point, direction: Vector) -> Self {
        Self { origin, direction }
    }

    pub fn t(&self, t: f32) -> Point {
        self.origin + self.direction * t
    }

    pub fn is_positive_side(&self, p: &Point) -> bool {
        let a = self.direction.y;
        let b = -self.direction.x;
        let c = self.direction.x * self.origin.y - self.direction.y * self.origin.x;
        let v = p.x * a + p.y * b + c;
        v < 0.0
    }
}

pub fn intersect_rays_t(ray1: &Ray, ray2: &Ray) -> Option<f32> {
    let p1 = ray1.origin;
    let d1 = ray1.direction;
    let p2 = ray2.origin;
    let d2 = ray2.direction;

    // Calculate the determinant to check if the rays are parallel
    let determinant = d1.cross(d2);
    if determinant.abs() < f32::EPSILON {
        // Parallel rays, no intersection
        return None;
    }

    // Solve for t1 and t2 parameters
    let diff = p2 - p1;

    Some(diff.cross(d2) / determinant)
}

pub fn intersect_rays(ray1: &Ray, ray2: &Ray) -> Option<Point> {
    intersect_rays_t(ray1, ray2).map(|t| ray1.t(t))
}

/*
 * Creates a resizing matrix with width/height relative to the parent
 * box and keepin the same transform as the parent.
 */
pub fn resize_matrix(
    parent_bounds: &Bounds,
    child_bounds: &Bounds,
    new_width: f32,
    new_height: f32,
) -> Matrix {
    let mut result = Matrix::default();
    let scale_width = new_width / child_bounds.width();
    let scale_height = new_height / child_bounds.height();

    let center = child_bounds.center();
    let mut parent_transform = parent_bounds.transform_matrix().unwrap_or_default();

    parent_transform.post_translate(center);
    parent_transform.pre_translate(-center);

    let parent_transform_inv = &parent_transform.invert().unwrap();
    let origin = parent_transform_inv.map_point(child_bounds.nw);

    let mut scale = Matrix::scale((scale_width, scale_height));
    scale.post_translate(origin);
    scale.post_concat(&parent_transform);
    scale.pre_translate(-origin);
    scale.pre_concat(parent_transform_inv);
    result.post_concat(&scale);
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_ray_parameter() {
        let r = Ray::new(Point::new(0.0, 0.0), Vector::new(0.5, 0.5));
        assert_eq!(r.t(1.0), Point::new(0.5, 0.5));
        assert_eq!(r.t(2.0), Point::new(1.0, 1.0));
        assert_eq!(r.t(-2.0), Point::new(-1.0, -1.0));
    }

    #[test]
    fn test_intersect() {
        // Test Cases for Ray-Ray Intersections
        // Simple Intersection at (2, 2)
        let r1 = Ray::new(Point::new(0.0, 0.0), Vector::new(1.0, 1.0));
        let r2 = Ray::new(Point::new(0.0, 4.0), Vector::new(1.0, -1.0));
        assert_eq!(intersect_rays(&r1, &r2), Some(Point::new(2.0, 2.0)));

        // Parallel Rays (No Intersection)
        let r1 = Ray::new(Point::new(0.0, 0.0), Vector::new(1.0, 1.0));
        let r2 = Ray::new(Point::new(0.0, 2.0), Vector::new(1.0, 1.0));
        assert_eq!(intersect_rays(&r1, &r2), None);

        // Coincident Rays (Infinite Intersections)
        let r1 = Ray::new(Point::new(0.0, 0.0), Vector::new(1.0, 1.0));
        let r2 = Ray::new(Point::new(1.0, 1.0), Vector::new(1.0, 1.0));
        assert_eq!(intersect_rays(&r1, &r2), None);

        let r1 = Ray::new(Point::new(1.0, 0.0), Vector::new(2.0, 1.0));
        let r2 = Ray::new(Point::new(4.0, 4.0), Vector::new(-1.0, -1.0));
        assert_eq!(intersect_rays(&r1, &r2), Some(Point::new(-1.0, -1.0)));

        let r1 = Ray::new(Point::new(1.0, 1.0), Vector::new(3.0, 2.0));
        let r2 = Ray::new(Point::new(4.0, 0.0), Vector::new(-2.0, 3.0));
        assert_eq!(
            intersect_rays(&r1, &r2),
            Some(Point::new(2.6153846, 2.0769231))
        );
    }

    #[test]
    fn test_vec_min_max() {
        assert_eq!(None, vec_min_max(&vec![]));
        assert_eq!(None, vec_min_max(&vec![None, None]));
        assert_eq!(Some((1.0, 1.0)), vec_min_max(&vec![None, Some(1.0)]));
        assert_eq!(
            Some((0.0, 1.0)),
            vec_min_max(&vec![
                Some(0.3),
                None,
                Some(0.0),
                Some(0.7),
                Some(1.0),
                Some(0.1)
            ])
        );
    }

    #[test]
    fn test_box_bounds() {
        let b1 = Bounds::new(
            Point::new(1.0, 5.0),
            Point::new(5.0, 5.0),
            Point::new(5.0, 1.0),
            Point::new(1.0, 1.0),
        );
        let b2 = Bounds::new(
            Point::new(3.0, 4.0),
            Point::new(4.0, 3.0),
            Point::new(3.0, 2.0),
            Point::new(2.0, 3.0),
        );
        let result = b1.box_bounds(&b2);
        assert_eq!(
            Some(Bounds::new(
                Point::new(2.0, 4.0),
                Point::new(4.0, 4.0),
                Point::new(4.0, 2.0),
                Point::new(2.0, 2.0),
            )),
            result
        )
    }

    #[test]
    fn test_bounds_distances() {
        let b1 = Bounds::new(
            Point::new(1.0, 1.0),
            Point::new(8.0, 1.0),
            Point::new(8.0, 10.0),
            Point::new(1.0, 10.0),
        );
        assert_eq!(b1.left(Point::new(4.0, 8.0)), 3.0);
        assert_eq!(b1.top(Point::new(4.0, 8.0)), 7.0);
        assert_eq!(b1.right(Point::new(7.0, 6.0),), 1.0);
        assert_eq!(b1.bottom(Point::new(7.0, 6.0),), 4.0);
    }

    #[test]
    fn test_transform_matrix() {
        let b = Bounds::new(
            Point::new(0.0, 0.0),
            Point::new(50.0, 0.0),
            Point::new(50.0, 50.0),
            Point::new(0.0, 50.0),
        );

        assert_eq!(b.width(), 50.0);
        assert_eq!(b.height(), 50.0);
        assert_eq!(b.transform_matrix().unwrap(), Matrix::default());

        let b = Bounds::new(
            Point::new(-25.0, 1.0),
            Point::new(1.0, -34.5),
            Point::new(27.0, 1.0),
            Point::new(1.0, 36.5),
        );

        assert!((b.width() - 44.0).abs() <= 0.1);
        assert!((b.height() - 44.0).abs() <= 0.1);

        let m = b.transform_matrix().unwrap();
        assert!((m.scale_x() - 0.59).abs() <= 0.1);
        assert!((m.skew_y() - -0.81).abs() <= 0.1);
        assert!((m.skew_x() - 0.59).abs() <= 0.1);
        assert!((m.scale_y() - 0.81).abs() <= 0.1);
        assert!((m.translate_x() - 0.0).abs() <= 0.1);
        assert!((m.translate_y() - 0.0).abs() <= 0.1);
    }
}
