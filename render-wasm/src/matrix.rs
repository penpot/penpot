// allowing dead code so we can have some API's that are not used yet without warnings
#![allow(dead_code)]
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

    pub fn translate(x: f32, y: f32) -> Self {
        Self::new(0.0, 0.0, 0.0, 0.0, x, y)
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

    pub fn product(&self, other: &Matrix) -> Matrix {
        let a = self.a * other.a + self.c * other.b;
        let b = self.b * other.a + self.d * other.b;
        let c = self.a * other.c + self.c * other.d;
        let d = self.b * other.c + self.d * other.d;
        let e = self.a * other.e + self.c * other.f + self.e;
        let f = self.b * other.e + self.d * other.f + self.f;
        Matrix::new(a, b, c, d, e, f)
    }

    pub fn as_bytes(&self) -> [u8; 24] {
        let mut result = [0; 24];
        result[0..4].clone_from_slice(&self.a.to_le_bytes());
        result[4..8].clone_from_slice(&self.b.to_le_bytes());
        result[8..12].clone_from_slice(&self.c.to_le_bytes());
        result[12..16].clone_from_slice(&self.d.to_le_bytes());
        result[16..20].clone_from_slice(&self.e.to_le_bytes());
        result[20..24].clone_from_slice(&self.f.to_le_bytes());
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_product() {
        let a = Matrix::new(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
        let b = Matrix::new(6.0, 5.0, 4.0, 3.0, 2.0, 1.0);

        assert_eq!(
            a.product(&b),
            Matrix::new(21.0, 32.0, 13.0, 20.0, 10.0, 14.0)
        );

        let a = Matrix::new(7.0, 4.0, 8.0, 3.0, 9.0, 5.0);
        let b = Matrix::new(7.0, 4.0, 8.0, 3.0, 9.0, 5.0);

        assert_eq!(
            a.product(&b),
            Matrix::new(81.0, 40.0, 80.0, 41.0, 112.0, 56.0)
        );
    }
}
