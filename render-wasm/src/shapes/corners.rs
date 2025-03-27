use skia_safe::Point;

pub type CornerRadius = Point;
pub type Corners = [CornerRadius; 4];

pub fn make_corners(raw_corners: (f32, f32, f32, f32)) -> Option<Corners> {
    let (r1, r2, r3, r4) = raw_corners;
    let are_straight_corners = r1.abs() <= f32::EPSILON
        && r2.abs() <= f32::EPSILON
        && r3.abs() <= f32::EPSILON
        && r4.abs() <= f32::EPSILON;

    if are_straight_corners {
        None
    } else {
        Some([
            (r1, r1).into(),
            (r2, r2).into(),
            (r3, r3).into(),
            (r4, r4).into(),
        ])
    }
}
