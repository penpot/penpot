#[derive(Debug, Clone)]
pub struct GlassEffect {
    pub radius: f32,
    pub refraction: f32,
    pub depth: f32,
    pub dispersion: f32,
    pub light_intensity: f32,
    pub light_angle: f32,
    pub hidden: bool,
}

pub const GLASS_SKSL: &str = "
uniform shader blurredImage;
uniform vec2  resolution;
uniform vec4  bounds;
uniform float radius;
uniform float refraction;
uniform float depth;
uniform float dispersion;
uniform float lightIntensity;
uniform float lightAngle;
uniform mat3  transform;

float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float sdf(vec2 xy) {
    vec2 center = bounds.xy + bounds.zw * 0.5;
    vec2 p = xy - center;
    return sdRoundedBox(p, bounds.zw * 0.5, radius);
}

vec2 calculateGradient(vec2 p) {
    const float eps = 0.5;
    float dx = sdf(p + vec2(eps, 0.0)) - sdf(p - vec2(eps, 0.0));
    float dy = sdf(p + vec2(0.0, eps)) - sdf(p - vec2(0.0, eps));
    return normalize(vec2(dx, dy) / (2.0 * eps));
}

vec3 getNormal(float sd, vec2 gradient, float thickness) {
    float n_cos = clamp((thickness + sd) / thickness, 0.0, 1.0);
    float n_sin = sqrt(max(1.0 - n_cos * n_cos, 0.0));
    return normalize(vec3(gradient.x * n_sin, gradient.y * n_sin, n_cos));
}

float lensHeight(float sd, float thickness) {
    if (sd >= 0.0)        return 0.0;
    if (sd < -thickness)  return thickness;
    float x = thickness + sd;
    return sqrt(max(thickness * thickness - x * x, 0.0));
}

vec4 computeGlass(float sd, vec2 fragCoord) {
    float thickness   = max(depth, 1.0);
    float base_height = thickness * 1.5;
    float ior         = max(refraction, 1.0001);
    float ca          = dispersion * 0.05;

    vec2  grad    = calculateGradient(fragCoord);
    vec3  normal  = getNormal(sd, grad, thickness);
    vec3  incident = vec3(0.0, 0.0, -1.0);

    float fresnel = pow(1.0 - abs(dot(incident, normal)), 3.0) * lightIntensity;

    vec3  refractDir = refract(incident, normal, 1.0 / ior);
    float h          = lensHeight(sd, thickness);
    float rLen       = (h + base_height) / max(dot(vec3(0.0, 0.0, -1.0), refractDir), 0.001);
    vec2  baseCoord  = fragCoord + refractDir.xy * rLen;
    vec2  uvBase     = baseCoord / resolution;

    vec2  caOffset = refractDir.xy * ca;
    float rCh = blurredImage.eval((uvBase - caOffset) * resolution).r;
    float gCh = blurredImage.eval( uvBase             * resolution).g;
    float bCh = blurredImage.eval((uvBase + caOffset)  * resolution).b;
    vec4  refractColor = vec4(rCh, gCh, bCh, 1.0);

    vec2  lightDir  = vec2(cos(lightAngle), sin(lightAngle));
    float rimLight  = max(dot(normal.xy, lightDir), 0.0) * lightIntensity;
    vec4  rimColor  = vec4(rimLight, rimLight, rimLight, 0.0);

    vec4 glassColor = mix(refractColor, rimColor, fresnel * 0.5);
    vec4 tint = vec4(lightIntensity * 0.3);
    return glassColor + tint * (1.0 - glassColor.a * 0.5);
}

vec4 main(vec2 fragCoord) {
    float d = sdf(fragCoord);
    if (d > 1.0) return vec4(0.0);

    const int S = 4;
    float w = 1.0 / float(S * S);
    vec4 color = vec4(0.0);
    for (int m = 0; m < S; m++) {
        for (int n = 0; n < S; n++) {
            vec2 off = vec2(float(m), float(n)) / float(S) - 0.5 / float(S);
            vec2 p = fragCoord + off;
            float sd = sdf(p);
            if (sd <= 0.0) {
                color += computeGlass(sd, p) * w;
            } else {
                color += vec4(0.0);
            }
        }
    }
    return color;
}
";
