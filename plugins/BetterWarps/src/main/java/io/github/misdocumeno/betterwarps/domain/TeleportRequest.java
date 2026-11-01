package io.github.misdocumeno.betterwarps.domain;

public record TeleportRequest(
    String username,
    GlobalLocation location,
    TeleportFeedback feedback
) {
}
