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

pub fn scale_corners(corners: &mut Corners, value: f32) {
    corners[0].x *= value;
    corners[0].y *= value;
    corners[1].x *= value;
    corners[1].y *= value;
    corners[2].x *= value;
    corners[2].y *= value;
    corners[3].x *= value;
    corners[3].y *= value;
}
