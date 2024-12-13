use skia_safe as skia;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Matrix {
    pub a: f32,
    pub b: f32,
    pub c: f32,
    pub d: f32,
    pub e: f32,
    pub f: f32,
}

impl Matrix {
    pub fn new(a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) -> Self {
        Self { a, b, c, d, e, f }
    }

    pub fn identity() -> Self {
        Self {
            a: 1.,
            b: 0.,
            c: 0.,
            d: 1.,
            e: 0.,
            f: 0.,
        }
    }

    pub fn to_skia_matrix(&self) -> skia::Matrix {
        let mut res = skia::Matrix::new_identity();

        let (translate_x, translate_y) = self.translation();
        let (scale_x, scale_y) = self.scale();
        let (skew_x, skew_y) = self.skew();
        res.set_all(
            scale_x,
            skew_x,
            translate_x,
            skew_y,
            scale_y,
            translate_y,
            0.,
            0.,
            1.,
        );

        res
    }

    pub fn no_translation(&self) -> Self {
        let mut res = Self::identity();
        res.c = self.c;
        res.b = self.b;
        res.a = self.a;
        res.d = self.d;
        res
    }

    fn translation(&self) -> (f32, f32) {
        (self.e, self.f)
    }

    fn scale(&self) -> (f32, f32) {
        (self.a, self.d)
    }

    fn skew(&self) -> (f32, f32) {
        (self.c, self.b)
    }
}
