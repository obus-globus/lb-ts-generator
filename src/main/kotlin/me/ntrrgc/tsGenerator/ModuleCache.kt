/*
 * Persistent per-class render cache for the regen.
 *
 * The regen's cost is the transitive reflection walk over ~56k classes in
 * TypeScriptGenerator. Most of those classes live in library jars (the JDK,
 * kotlin-stdlib, fastutil, netty, guava, log4j, lwjgl, icu4j, the GraalVM/Truffle
 * runtime, ...) that do NOT change between regens of the same dependency set --
 * typically only `net.ccbluex.*` (and, on an MC bump, `net.minecraft.*` + the
 * MC-coupled jars: mojang, viaversion, fabric, sodium, iris) move. Re-reflecting
 * the unchanged majority every run is pure waste.
 *
 * This cache lets a regen reuse the previous run's *rendered* `.d.ts` text for a
 * class when all of:
 *   (a) the generator jar itself is unchanged (its own content sha), and
 *   (b) the class's own source jar is byte-for-byte unchanged (content sha), and
 *   (c) every jar the class DIRECTLY imports from is also unchanged (content sha),
 * hold. On a hit the class is not reflected at all: the prior rendered module
 * text is read back from `<cacheDir>/raw/<path>` and dropped straight into the
 * output.
 *
 * Soundness. A rendered module bakes in `import` paths and collision aliases
 * resolved against the *names* of its DIRECT dependent types (the import lines).
 * Reusing the text is sound iff that direct dependent set, and each dependent's
 * path/name, are unchanged. Rule (b) keeps the class's own bytecode -- hence its
 * dependent set and collision aliases -- identical; rule (c) keeps every imported
 * dependent present at the same path (an unchanged jar can't have renamed/removed
 * the class). There is NO package allow-list: gating is purely by content hash,
 * which is what makes it correct (any changed jar -> sha mismatch -> miss ->
 * re-render). The generator is deterministic (see determinismTests), so identical
 * jar + identical generator => identical output.
 *
 * Known narrow gap: if a DIRECT dependent reflects fine on the run that wrote the
 * cache but (non-deterministically) fails reflection on the reusing run with an
 * unchanged jar, the reusing run would drop that dependent's module while the
 * cached text still imports it -> a dangling import. This requires
 * non-deterministic reflection failure on an unchanged jar; the typecheck gate's
 * import-resolution check (Part C) catches it.
 *
 * The cache is OFF unless the env var `TSGEN_CACHE_DIR` points at a directory;
 * normal `gradlew test` and any non-regen embedding are completely unaffected.
 */
package me.ntrrgc.tsGenerator

import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlin.reflect.KClass

class ModuleCache private constructor(
    private val cacheDir: File,
    /** sha of the generator jar this run is using. */
    val generatorSha: String,
    /** generator sha recorded by the run that wrote the cache (or null). */
    private val priorGeneratorSha: String?,
    /** jarKey -> content sha, as recorded by the prior run. */
    private val priorJarShas: Map<String, String>,
    /** binary class name -> entry, as recorded by the prior run. */
    private val priorEntries: Map<String, Entry>,
) {
    data class Entry(val jarKey: String, val path: String, val depFqns: List<String>)

    private val loader: ClassLoader = Thread.currentThread().contextClassLoader

    // memoized per-run lookups
    private val jarKeyByLocation = HashMap<String, String?>()
    private val shaByJarKey = HashMap<String, String?>()

    // accumulated state for the cache we will write at the end of this run
    private val newJarShas = HashMap<String, String>()
    private val newEntries = LinkedHashMap<String, Entry>()
    private val rawToWrite = ArrayList<Pair<String, String>>() // path -> moduleText

    var reused = 0; private set
    var recorded = 0; private set

    /** Whether the whole prior cache is usable at all (generator unchanged). */
    private val generatorMatches: Boolean = priorGeneratorSha != null && priorGeneratorSha == generatorSha

    /**
     * Resolve (jarKey, contentSha) for the jar a class came from, memoized.
     * JDK classes (loaded from the runtime image, codeSource == null) are keyed
     * by the JVM version string -- stable per JDK, which is what we want.
     * Returns null for classes whose origin can't be content-addressed.
     */
    fun jarKeyAndSha(klass: KClass<*>): Pair<String, String>? {
        val loc: URL? = try {
            klass.java.protectionDomain?.codeSource?.location
        } catch (t: Throwable) {
            null
        }
        if (loc == null) {
            // Bootstrap/platform classes (java.*, javax.*, jdk.*, sun.*) come from
            // the jrt image with no codeSource. Treat the whole JDK as one stable
            // unit keyed by its version.
            val n = klass.java.name ?: return null
            if (!(n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") || n.startsWith("sun."))) {
                return null
            }
            val key = "__jdk__"
            val sha = shaByJarKey.getOrPut(key) {
                (System.getProperty("java.vm.version") ?: System.getProperty("java.version") ?: "unknown")
            } ?: return null
            return key to sha
        }
        if (loc.protocol != "file") return null
        val locStr = loc.toString()
        val key = jarKeyByLocation.getOrPut(locStr) {
            try {
                File(loc.toURI()).name
            } catch (t: Throwable) {
                null
            }
        } ?: return null
        val sha = shaByJarKey.getOrPut(key) {
            try {
                shaOfFile(File(loc.toURI()))
            } catch (t: Throwable) {
                null
            }
        } ?: return null
        return key to sha
    }

    /**
     * If [klass] can be reused verbatim from the prior run, return its cached
     * (path, moduleText, resolved dependency classes); else null.
     *
     * Reuse is sound only when:
     *   - the generator jar is unchanged (whole-cache key), and
     *   - the class's own source jar is unchanged (its bytecode, hence its
     *     rendered definition and its dependency set, are identical), and
     *   - EVERY directly-imported dependency's source jar is unchanged. The
     *     cached text imports dependents by name/path; a dependent that was
     *     renamed or removed in a bumped jar would dangle. A dependency jar being
     *     unchanged guarantees that dependent still exists at the same path.
     * Any dependency that can't be content-addressed (no file codeSource) is
     * treated conservatively as changed -> miss.
     *
     * A hit carries the entry forward into the new cache (its raw file is already
     * on disk and unchanged, so it is not rewritten) and returns the resolved
     * dependency KClasses so the caller can continue the walk without re-resolving.
     */
    fun tryReuse(klass: KClass<*>): Reused? {
        if (!generatorMatches) return null
        val fqn = klass.java.name ?: return null
        val entry = priorEntries[fqn] ?: return null
        val (jarKey, sha) = jarKeyAndSha(klass) ?: return null
        if (entry.jarKey != jarKey) return null
        if (priorJarShas[jarKey] != sha) return null

        val resolvedDeps = ArrayList<KClass<*>>(entry.depFqns.size)
        for (depFqn in entry.depFqns) {
            val dep = resolveKClass(depFqn) ?: return null
            val (dk, ds) = jarKeyAndSha(dep) ?: return null
            if (priorJarShas[dk] != ds) return null
            resolvedDeps += dep
        }

        val rawFile = File(cacheDir, "raw/${entry.path}")
        if (!rawFile.isFile) return null
        val text = try {
            rawFile.readText()
        } catch (t: Throwable) {
            return null
        }
        newJarShas[jarKey] = sha
        newEntries[fqn] = entry
        reused++
        return Reused(entry.path, text, resolvedDeps)
    }

    data class Reused(val path: String, val moduleText: String, val deps: List<KClass<*>>)

    /** Resolve a stored binary class name back to a KClass to continue the walk. */
    fun resolveKClass(binaryName: String): KClass<*>? = try {
        Class.forName(binaryName, false, loader).kotlin
    } catch (t: Throwable) {
        null
    }

    /**
     * Record a freshly-rendered module for the next run. Must be called only
     * after the whole walk completed, so [moduleText] (lazy) resolves its imports
     * against a fully-populated module set. Classes that can't be content-
     * addressed (no file codeSource and not the JDK) are simply not cached.
     */
    fun recordFresh(klass: KClass<*>, path: String, depFqns: List<String>, moduleText: String) {
        val fqn = klass.java.name ?: return
        val (jarKey, sha) = jarKeyAndSha(klass) ?: return
        newJarShas[jarKey] = sha
        newEntries[fqn] = Entry(jarKey, path, depFqns)
        rawToWrite += path to moduleText
        recorded++
    }

    /** Persist the new cache (manifest + raw files for freshly-rendered modules). */
    fun flush() {
        cacheDir.mkdirs()
        File(cacheDir, "meta.txt").writeText("generatorSha=$generatorSha\n")
        File(cacheDir, "jars.tsv").writeText(
            newJarShas.entries.joinToString("\n", postfix = "\n") { "${it.key}\t${it.value}" }
        )
        File(cacheDir, "manifest.tsv").writeText(
            newEntries.entries.joinToString("\n", postfix = "\n") { (fqn, e) ->
                "$fqn\t${e.jarKey}\t${e.path}\t${e.depFqns.joinToString(",")}"
            }
        )
        for ((path, text) in rawToWrite) {
            val f = File(cacheDir, "raw/$path")
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
        println("ModuleCache: reused=$reused recorded(fresh)=$recorded jars=${newJarShas.size} (generatorMatch=$generatorMatches)")
    }

    companion object {
        /**
         * Open (and load if present) the cache at [dir]. [generatorSelf] is the
         * generator's own jar location, hashed as the cache-wide invalidation key.
         */
        fun open(dir: File, generatorSelf: URL?): ModuleCache {
            val generatorSha = try {
                if (generatorSelf != null && generatorSelf.protocol == "file") {
                    shaOfFile(File(generatorSelf.toURI())) ?: "unknown"
                } else "unknown"
            } catch (t: Throwable) {
                "unknown"
            }

            var priorGen: String? = null
            val priorJars = HashMap<String, String>()
            val priorEntries = HashMap<String, Entry>()

            val meta = File(dir, "meta.txt")
            if (meta.isFile) {
                meta.readLines().forEach { line ->
                    if (line.startsWith("generatorSha=")) priorGen = line.substringAfter('=').trim()
                }
            }
            val jars = File(dir, "jars.tsv")
            if (jars.isFile) {
                jars.readLines().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val p = line.split('\t')
                    if (p.size >= 2) priorJars[p[0]] = p[1]
                }
            }
            val manifest = File(dir, "manifest.tsv")
            if (manifest.isFile) {
                manifest.readLines().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val p = line.split('\t')
                    if (p.size >= 3) {
                        val deps = if (p.size >= 4 && p[3].isNotEmpty()) p[3].split(',') else emptyList()
                        priorEntries[p[0]] = Entry(p[1], p[2], deps)
                    }
                }
            }
            return ModuleCache(dir, generatorSha, priorGen, priorJars, priorEntries)
        }

        private fun shaOfFile(f: File): String? {
            if (!f.isFile) return null
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
