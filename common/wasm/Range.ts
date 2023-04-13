export const EPSILON: f32 = 0.001

export function clamp(x: f32, min: f32, max: f32): f32 {
  if (x < min) return min
  if (x > max) return max
  return x
}

export function from(x: f32, min: f32, max: f32): f32 {
  return (x - min) / (max - min)
}

export function almostEqual(a: f32, b: f32, epsilon: f32 = EPSILON): boolean {
  return Mathf.abs(a - b) <= epsilon * Mathf.max(1.0, Mathf.max(Mathf.abs(a), Mathf.abs(b)))
}
