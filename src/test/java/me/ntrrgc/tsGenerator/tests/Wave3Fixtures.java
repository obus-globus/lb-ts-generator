package me.ntrrgc.tsGenerator.tests;

import java.util.function.UnaryOperator;

// Fixtures for the A-series (2026-07-16 audit) wave-3 generator fixes that
// live on the Java-reflection path (Kotlin fixtures in wave3RegressionTests.kt).

// A17: a static method's OWN generics must be declared, not erased to their
// bounds (`make(param: Class<Object>): Object | null` hid both the parameter
// link and the non-null return).
class GenericStaticFixtures {
    public static <T> T make(Class<T> cls) { return null; }
    public static <T extends Comparable<T>> T pickFirst(T a, T b) { return a; }
}

// A17: the SAM of UnaryOperator<T> is declared on its SUPERinterface
// Function<T, R>; the substitution must be composed onto the declaring class
// or R erases (`(param0: Component) => Object | null`).
class UnaryOperatorUser {
    public void applyOp(UnaryOperator<String> f) {}
}

// A15: nashorn dual surface, Java side. GraalJS nashorn-compat exposes a
// getter as BOTH `getTitle()` and the bean property `title`; kotlin-reflect
// only synthesizes properties for same-named backing fields, so field-less
// getters were method-only.
class JavaBeanDual {
    private String stash;

    // No backing field named `title` -> the property form must be injected.
    public String getTitle() { return stash; }

    // Getter + setter pair -> injected property is writable.
    public String getMood() { return stash; }
    public void setMood(String mood) { this.stash = mood; }

    // Boolean is-getter -> `ready`.
    public boolean isReady() { return true; }

    // A method named like the bean already exists -> skip (property-vs-method
    // is TS2416 across extends / an F7 collision within the class).
    public int getValue() { return 0; }
    public int value() { return 0; }

    // Backing field named like the bean exists (private field + getter) ->
    // the bean branch already emits the property; no duplicate.
    public String getStash() { return stash; }
}
