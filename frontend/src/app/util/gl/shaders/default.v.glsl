#version 300 es

precision highp float;

uniform vec4 u_vbox;
uniform vec2 u_position;
uniform vec2 u_size;

// out vec2 v_texCoord;

vec2 get_vertex_position(int id) {
    if(id == 0) {
        return vec2(0.0f, 0.0f);
    } else if(id == 1) {
        return vec2(1.0f, 0.0f);
    } else if(id == 2) {
        return vec2(0.0f, 1.0f);
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

vec2 from(vec2 v, vec2 min, vec2 max) {
    return (v - min) / (max - min);
}

void main() {
    vec2 vertex = get_vertex_position(gl_VertexID);
    vec2 position = vertex * from(u_size, vec2(0), u_vbox.zw) + from(u_position, u_vbox.xy, u_vbox.xy + u_vbox.zw); // 0,1 
    gl_Position = vec4(mix(vec2(-1.0, 1.0), vec2(1.0, -1.0), position), 0.0f, 1.0f);
    // gl_Position = vec4(((get_base_position(gl_VertexID) * u_size + u_position) / u_vbox.zw), 0.0f, 1.0f);
    // v_texCoord = get_tex_position(gl_VertexID);
}