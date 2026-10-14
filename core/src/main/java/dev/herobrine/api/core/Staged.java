package dev.herobrine.api.core;

/** Action handlers return this to report a stage stronger than EXECUTED_LOCALLY. */
public record Staged(Object result, Stage stage) {}
