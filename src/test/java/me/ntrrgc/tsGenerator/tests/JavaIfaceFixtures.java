package me.ntrrgc.tsGenerator.tests;

// Fixtures for the B-fix transitive walk over a JAVA interface hierarchy. The
// real regression (an enum packet type missing PacketType.state(), two hops up)
// only reproduces with Java interfaces, where kotlin-reflect's `supertypes` can
// under-report — so these are Java, not Kotlin. Top-level (package-private) so
// the emitted binary name is the bare `JEnumImpl`, not a nested `Outer$Inner`.

interface JBase {
    String jBase();
}

interface JMid extends JBase {
    String jMid();
}

// A Java enum whose concrete interface implementations kotlin-reflect does not
// surface as declared members — must still gain jMid() (direct) AND jBase()
// (two hops, via the Java interface walk).
enum JEnumImpl implements JMid {
    A;

    @Override
    public String jBase() {
        return "";
    }

    @Override
    public String jMid() {
        return "";
    }
}
