package io.github.misdocumeno.betterwarps.domain;

public record TitleFeedback(
    String title,
    String subtitle,
    long fadeIn,
    long stay,
    long fadeOut
) {
}
