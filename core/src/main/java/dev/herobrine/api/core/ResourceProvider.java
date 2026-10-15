package dev.herobrine.api.core;

/** Implemented by mods that want to expose their own resources and actions. */
public interface ResourceProvider {
    /** Namespace for everything this provider registers; ids become {@code modId.name}. */
    String modId();

    void register(ApiRegistrar registrar);
}
