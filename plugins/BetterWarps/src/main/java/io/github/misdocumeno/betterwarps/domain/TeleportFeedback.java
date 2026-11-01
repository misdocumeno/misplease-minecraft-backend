package io.github.misdocumeno.betterwarps.domain;

public record TeleportFeedback(
    String chat,
    TitleFeedback title,
    SoundFeedback sound
) {
}
