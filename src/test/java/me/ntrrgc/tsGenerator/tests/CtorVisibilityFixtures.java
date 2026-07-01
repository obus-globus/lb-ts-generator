package me.ntrrgc.tsGenerator.tests;

// Java constructors report a *null* KVisibility through kotlin-reflect. The
// generator must treat null visibility as public, otherwise every class with a
// Java constructor is emitted with a private constructor and subclasses can no
// longer extend it (the TS2675 regression that shipped once and was caught in
// regen-validation, never by a unit test).
class JavaBaseWithCtor {
    public JavaBaseWithCtor(int a) {
    }
}

// A generic constructor introduces a constructor-level type parameter (`M`)
// that cannot be declared on the TS class. It must be substituted to its bound
// (here the implicit `Object`), never emitted as an undeclared `M` (TS2304).
class JavaGenericCtor {
    <M> JavaGenericCtor(M item) {
    }
}
