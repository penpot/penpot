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
uniform vec4 u_color;

void main() {
    // Si es un rect o un frame, simplemente asignamos el color al fragColor.
    if (u_type == type_rect || u_type == type_frame) {
        fragColor = u_color;
    // Si es un circulo, comprobamos que el pixel este dentro del circulo, en caso
    // contrario descartamos el pixel.
    } else if (u_type == type_circle) {
        if (length(v_texCoord - 0.5) > 0.5) {
            discard;
        }
        if(length(v_texCoord - 0.5f) > 0.45f) {

            fragColor = vec4(1.0, 0.0, 0.0, 1.0);
        } else if(length(v_texCoord - 0.5f) > 0.4f) {
            fragColor = vec4(1.0f, 0.0f, 1.0f, 1.0f);
        } else {
            fragColor = u_color;
        }
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