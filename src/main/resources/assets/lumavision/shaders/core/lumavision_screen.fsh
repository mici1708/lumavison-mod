#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec4 normal;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }

    float boost = vertexColor.a;
    color *= vec4(vertexColor.rgb, 1.0) * ColorModulator;
    color.rgb *= 1.0 + boost * 2.0;
    color.rgb = min(color.rgb, vec3(1.0));
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    color.a = 1.0;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
