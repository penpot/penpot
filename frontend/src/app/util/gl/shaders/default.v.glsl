#version 300 es

precision highp float;

uniform vec4 u_vbox;
uniform vec2 u_position;
uniform vec2 u_size;
uniform float u_rotation;
uniform mat3 u_projection;

out vec2 v_texCoord;

vec2 get_vertex_position(int id) {
    if(id == 0) {
        return vec2(-1.0, -1.0);
    } else if(id == 1) {
        return vec2(1.0, -1.0);
    } else if(id == 2) {
        return vec2(-1.0, 1.0);
    } else if(id == 3) {
        return vec2(1.0, 1.0);
    } else {
        return vec2(0.0f, 0.0f);
    }
}

void main() {
    vec2 center = u_size * 0.5;
    vec2 position = u_position - vec2(u_vbox.xy);

    float c = cos(u_rotation);
    float s = sin(u_rotation);

    mat2 rotation = mat2(c, s, -s, c);
    mat2 scale = mat2(
        u_size.x * 0.5, 0.0f,
        0.0f, u_size.y * 0.5
    );
    mat2 rotation_scale = rotation * scale;

    vec2 vertex = get_vertex_position(gl_VertexID);

    vec2 vertex_rotated_scaled = rotation_scale * vertex;
    vec2 vertex_positioned = center + vertex_rotated_scaled + position;

    vec3 projected = u_projection * vec3(vertex_positioned, 1.0);

    gl_Position = vec4(projected, 1.0f);
    v_texCoord = (vertex + 1.0) * 0.5;
}