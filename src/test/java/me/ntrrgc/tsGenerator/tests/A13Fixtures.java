package me.ntrrgc.tsGenerator.tests;

import java.util.HashMap;
import java.util.Map;

// Fixtures for A13 (structural JavaMap) that live on the Java-reflection
// path (Kotlin fixtures in javaMapRegressionTests.kt).

// A13: a raw (unparameterized) Map reference — kotlin-reflect raw-bounds the
// missing arguments to Object|null (arguments.size == 2), so it takes the
// normal path: JavaMap<Object | null, Object | null>. (The arity fallback is
// exercised by OneArgMap below.)
@SuppressWarnings("rawtypes")
class RawMapHolder {
    public Map raw;

    public Map giveRaw() { return null; }
}

// A13: a 1-arg Map subtype (the fastutil Int2ObjectMap<V> shape: the key is
// a primitive/fixed type absent from the subtype's own type arguments).
// References carry ONE argument -> the `arguments.size < 2` fallback.
interface OneArgMap<V> extends Map<String, V> {}

class OneArgMapHolder {
    public OneArgMap<String> byId;
}

// A13 x A14: a Map SUBTYPE class. References to it render structurally
// through mapFromKType (JavaMap<any, any> — its own argument count is 0);
// the class itself still gets its A14 statics-only module, whose factory
// return routes through mapFromKType too.
class MapSubtypeFixture extends HashMap<String, String> {
    public static MapSubtypeFixture create() { return new MapSubtypeFixture(); }
}

class MapSubtypeHolder {
    public MapSubtypeFixture sub() { return null; }
}
