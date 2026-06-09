package me.ntrrgc.tsGenerator.tests;

/**
 * SAM with a boxed Boolean return and a parameterized foreign type parameter.
 * Rendered as an arrow, the return type must map to `boolean` (not leak
 * `kotlin.Boolean`) and JavaBox must be imported and referenced by simple name.
 */
@FunctionalInterface
public interface BoxedCheck {
    Boolean check(JavaBox<String> box);
}
