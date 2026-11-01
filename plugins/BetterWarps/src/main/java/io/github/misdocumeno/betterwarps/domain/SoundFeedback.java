package io.github.misdocumeno.betterwarps.domain;

public record SoundFeedback(
    String sound,
    Float volume,
    Float pitch
) {
    public SoundFeedback {
        if (volume == null) volume = 1f;
        if (pitch == null) pitch = 1f;
    }
}
