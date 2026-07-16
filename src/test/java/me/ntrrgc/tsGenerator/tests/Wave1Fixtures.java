package me.ntrrgc.tsGenerator.tests;

import java.util.Comparator;
import java.util.List;

// Fixtures for the A-series (2026-07-16 audit) wave-1 generator fixes. Java,
// not Kotlin, because every one of these bugs lives on the Java-reflection
// path (statics, SAM detection, getFields) that Kotlin fixtures bypass.

// A1: java.util.Comparator redeclares equals(Object) abstract; the SAM finder
// must skip Object-method signatures and land on compare(T,T).
class ComparatorUser {
    public void sortWith(Comparator<String> cmp) {}
}

// A2: reflection-path arrays.
class StaticArrayFixtures {
    // Reference-array Class: must render string[], not (Object | null)[].
    public static void main(String[] args) {}

    // GenericArrayType (T[] erases to Object): the arrayness itself must
    // survive as (Object | null)[] with a rest param, not scalar Object|null.
    @SafeVarargs
    public static <T> void addAll(T... items) {}

    // Parameterized GenericArrayType: element renders through the normal
    // machinery (List<String>[] -> string[][]).
    public static void grids(List<String>[] tables) {}
}

// A4: distinct Java overloads that collapse to one TS line must emit once.
class StaticOverloadCollapse {
    public static double f(float v) { return v; }
    public static double f(double v) { return v; }
}

// A4: an inherited public constant next to a shadowing redeclaration must
// emit ONE `static ZERO` on the subclass (the subclass's own type).
class ConstBase {
    public static final int ZERO = 0;
}

class ConstShadow extends ConstBase {
    public static final long ZERO = 1L;
}
