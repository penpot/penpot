#version 300 es

const int type_rect    = 0;
const int type_circle  = 1;
const int type_group   = 2;
const int type_path    = 3;
const int type_text    = 4;
const int type_image   = 5;
const int type_svg_raw = 6;
const int type_bool    = 7;
const int type_frame   = 8;

precision highp float;

out vec4 fragColor;

in vec2 v_texCoord;

uniform int u_type;
uniform vec2 u_size;
uniform vec4 u_color;
uniform vec4 u_border;

bool isRoundedRect(in vec4 border) {
    return border.x > 0.0 || border.y > 0.0 || border.z > 0.0 || border.w > 0.0;
}

// Thanks to Iñigo Quilez for this awesome functions.
// @see https://iquilezles.org/articles/distfunctions2d/
float sdRoundBox(in vec2 p, in vec2 b, in vec4 r) {
    r.xy = (p.x > 0.0f) ? r.xy : r.zw;
    r.x = (p.y > 0.0f) ? r.x : r.y;
    vec2 q = abs(p) - b + r.x;
    return min(max(q.x, q.y), 0.0f) + length(max(q, 0.0f)) - r.x;
}

float sdCircle(in vec2 p, in float r) {
    return length(p) - r;
}

void main() {
    // Si es un rect o un frame, simplemente asignamos el color al fragColor.
    if (u_type == type_rect || u_type == type_frame) {
        if (isRoundedRect(u_border)) {
            if (sdRoundBox(v_texCoord - 0.5, vec2(0.5), u_border / u_size.x) > 0.0) {
                discard;
            }
            fragColor = u_color;
        } else {
            fragColor = u_color;
        }
    // Si es un circulo, comprobamos que el pixel este dentro del circulo, en caso
    // contrario descartamos el pixel.
    } else if (u_type == type_circle) {
        if (sdCircle(v_texCoord - 0.5, 0.5) > 0.0) {
            discard;
        }
        fragColor = u_color;
    // Para cualquier otro elemento no soportado pintamos una especie de rejilla
    // raruna.
    } else {
        fragColor = vec4(
            round(mod(v_texCoord.x, 0.1) * 10.0), 
            round(mod(v_texCoord.y, .1) * 10.0), 
            0.0, 
            1.0
        );
    }
}