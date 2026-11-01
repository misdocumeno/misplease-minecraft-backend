package io.github.misdocumeno.betterwarps.domain;

public record GlobalLocation(
    String server,
    String world,
    double x,
    double y,
    double z,
    float yaw,
    float pitch
) {
}

