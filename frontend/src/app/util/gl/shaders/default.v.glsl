#version 300 es

precision highp float;

uniform vec2 u_screenSize;
uniform vec2 u_position;
uniform vec2 u_size;

// out vec2 v_texCoord;

vec2 get_base_position(int id) {
    if(id == 0) {
        return vec2(-1.0f, -1.0f);
    } else if(id == 1) {
        return vec2(1.0f, -1.0f);
    } else if(id == 2) {
        return vec2(-1.0f, 1.0f);
    } else if(id == 3) {
        return vec2(1.0f, 1.0f);
    } else {
        return vec2(0.0f, 0.0f);
    }
}

/*
vec2 get_tex_position(int id) {
    if(id == 0) {
        return vec2(0.0f, 1.0f);
    } else if(id == 1) {
        return vec2(1.0f, 1.0f);
    } else if(id == 2) {
        return vec2(0.0f, 0.0f);
    } else if(id == 3) {
        return vec2(1.0f, 0.0f);
    } else {
        return vec2(0.0f, 0.0f);
    }
}
*/

void main() {
    gl_Position = vec4((get_base_position(gl_VertexID) * u_size + u_position) / u_screenSize, 0.0f, 1.0f);
    // v_texCoord = get_tex_position(gl_VertexID);
}