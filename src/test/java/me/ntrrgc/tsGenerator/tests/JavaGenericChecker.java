package me.ntrrgc.tsGenerator.tests;

/**
 * Java-style generic functional interface with a default method that
 * references the interface's type parameter — the shape of
 * java.util.function.Predicate. A non-generic implementor must emit the
 * default method with the supertype's actual type argument substituted for T.
 */
@FunctionalInterface
public interface JavaGenericChecker<T> {
    boolean test(T value);

    default JavaGenericChecker<T> and(JavaGenericChecker<? super T> other) {
        return this;
    }
}
