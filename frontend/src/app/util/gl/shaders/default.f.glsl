#version 300 es

precision highp float;

// in vec2 v_texCoord;

// uniform sampler2D u_texture;
uniform vec4 u_color;

out vec4 fragColor;

void main() {
    // fragColor = texture(u_framebuffer, v_texCoord) + texture(u_texture, v_texCoord);
    // fragColor = texture(u_texture, v_texCoord);
    // Pintamos rosita
    fragColor = u_color;
    // fragColor = vec4(1.0, 0.0, 1.0, 1.0);
}