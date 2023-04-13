export const DEG_TO_RAD: f32 = Mathf.PI / 180;
export const RAD_TO_DEG: f32 = 180 / Mathf.PI;

export function degreesToRadians(degrees: f32): f32 {
  return degrees * DEG_TO_RAD;
}

export function radiansToDegrees(radians: f32): f32 {
  return radians * RAD_TO_DEG;
}
