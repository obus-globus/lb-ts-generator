package me.ntrrgc.tsGenerator.tests;

/**
 * A functional interface whose SAM references its own type. Rendering the
 * arrow naively recurses forever; the generator must fall back to the nominal
 * name for the self-reference.
 */
@FunctionalInterface
public interface Combiner {
    Combiner combine(Combiner other);
}
