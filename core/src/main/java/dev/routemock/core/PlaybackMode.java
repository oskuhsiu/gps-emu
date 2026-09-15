package dev.routemock.core;

/** How playback continues after traversing the base route geometry. */
public enum PlaybackMode {
    ONCE,
    PING_PONG,
    LOOP
}
