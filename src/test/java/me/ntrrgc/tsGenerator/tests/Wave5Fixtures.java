package me.ntrrgc.tsGenerator.tests;

// Fixtures for the wave-5 fixes (A11 @JvmOverloads optionals + A17 emission
// nits N1-N4) that live on the Java-reflection path (Kotlin fixtures in
// wave5RegressionTests.kt).

// A17-N3: Java interface static METHODS are not inherited by implementers
// (JLS 8.4.8). The old interfaceSupertypes merge in staticMethodsOf copied
// them onto every implementing class anyway, typing runtime-uncallable
// statics (Java.type('...MutableComponent').literal(...) fails at runtime).
interface WithStaticIface {
    static int make() { return 1; }
}

class WithStaticImpl implements WithStaticIface {
}

// A17-N3 non-regression: interface CONSTANTS genuinely ARE inherited
// (klass.java.fields includes them) and must keep rendering on implementers.
interface WithConstIface {
    int LIMIT = 3;
}

class WithConstImpl implements WithConstIface {
}

// A17-N2 rider: a `foo$suspendImpl` static bridge (public in some compiler
// outputs, e.g. the 7 shipped mlkit files) is open-suspend-fn compiler
// bookkeeping and must be filtered by SYNTHETIC_MEMBER_REGEX. Declared with
// an explicit `$` name because modern kotlinc makes its own bridge
// non-public (invisible to klass.java.methods), so a Kotlin fixture cannot
// reproduce the leak.
class SuspendImplLeak {
    public static Object run$suspendImpl(SuspendImplLeak self, Object completion) { return null; }
    public static int keepMe() { return 1; }
}

// A17-N4 (anon/synthetic guards): a Java lambda's runtime class is a hidden
// class - ACC_SYNTHETIC but NOT anonymous/local - the exact flag combination
// of javac's constructor access-tag classes (BestCandidateSampling$1), which
// used to slip both guards and render their raw binary name (TS2304).
class LambdaClassProvider {
    static final Runnable HOOK = () -> {};
}
