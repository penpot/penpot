export function linear(x: f32, a: f32, b: f32): f32 {
  return (1 - x) * a + x * b;
}

export function quadratic(x: f32, a: f32, b: f32, c: f32): f32 {
  return linear(x, linear(x, a, b), linear(x, b, c))
}

export function cubic(x: f32, a: f32, b: f32, c: f32, d: f32): f32 {
  return linear(x, quadratic(x, a, b, c), quadratic(x, b, c, d))
}

export default {
  linear,
  quadratic,
  cubic
}
