# Lumance workflow

PaperMC-style pipeline, custom-built: patch Minecraft sources, compile, run.

## How it works

- The build downloads the **vanilla server bundle from Mojang** (pinned by SHA-1),
  extracts the nested server jar + libraries, and decompiles it with Vineflower.
- You edit the decompiled sources; `genPatches` turns your edits into `patches/*.patch`.
- The build recompiles everything, diffs vanilla vs patched into a small **overlay**,
  and assembles `lumance-<mc>-server.jar` containing only the launcher + overlay
  (no Mojang code redistributed).
- On first run the bootstrap re-downloads vanilla from Mojang, applies the overlay,
  verifies hashes, and launches `net.minecraft.server.Main` in a fresh JVM with
  a real `-classpath` (paperclip-style: never in-process classloading, so the
  server behaves exactly like a vanilla install). Later starts reuse the cache.

## Toolchain notes

- Decompile fixups come from NeoForge's NeoForm artifact (`neoformVersion` in
  `gradle.properties`), fetched automatically by `fetchNeoForm` during `setup`
  and applied onto the raw decompile. They only fix decompiler output so it
  compiles; Lumance's own changes live in `patches/` on top of them.
- Decompiler is pinned to the Vineflower version NeoForm uses for this Minecraft
  version. (NeoForm itself is mappings-only and irrelevant now that Mojang ships
  unobfuscated jars; ForgeFlower is unmaintained since 2023 and can't read
  modern class files.)
- Compile-only annotations (`jsr305`, JetBrains annotations) match NeoForm's set
  for this version; they are needed to compile but never ship.
- Patches are applied and generated with DiffPatch (the same engine NeoForm
  uses), in fuzzy-offset mode — no git required anywhere in the workflow.

## Requirements

- JDK 25 (Minecraft 26.x needs it)

## Workflow

```bash
gradlew setup          # download + decompile + apply patches -> work/main/java (one-time, slow)
# ... edit files under work/main/java, add classes under src/lumance/java ...
gradlew genPatches      # regenerate patches/*.patch from your edits
gradlew build           # compile + repack + overlay -> build/dist/lumance-*-server.jar
```

Other tasks:

```bash
gradlew runServer              # run the patched server straight from the build (working dir: run/)
gradlew runServer -PserverArgs="nogui"   # pass server args
gradlew setup -PrefreshSources # discard local edits and re-extract clean sources
```

## Running a release

Copy `build/dist/lumance-*-server.jar` (plus `run.sh` / `run.bat`) into an empty folder:

```bash
java -jar lumance-26.3-server.jar nogui
```

First start downloads ~60 MB from Mojang into `cache/` and extracts `libraries/`.
Accept the EULA (`eula.txt`) and restart, like vanilla.

The console speaks in color (Paper-style highlighted levels). The config lives
in `server-files/log4j2.xml`, ships inside the bootstrap, and overrides
Mojang's logging config at launch - the file log keeps vanilla's plain format.
On Windows the built-in mod enables ANSI rendering at startup, best effort.

## Fabric mods (built-in + user mods)

Every boot runs through Fabric Loader (Knot): mod discovery, entrypoints,
SpongePowered Mixins (plus MixinExtras) and access wideners all work on top of
the patched server. Lumance itself ships as a built-in mod, so its mixins and
entrypoint apply with zero setup, and Fabric API ships built in too - mods
that need it just work, no `mods/` juggling. Drop extra mod jars into `mods/`
like a stock Fabric server and they load alongside both. Loader, API and
library versions are pinned in `gradle.properties` (matching the official
fabric-example-mod for this game version) and hash-verified on download.
Bundled mods load via `fabric.addMods` from `cache/`; they are never placed
on the game classpath. There is no intermediary step: this tree is
unobfuscated, and Knot boots to Done without it.

Lumance's own mod lives in `src/main/java` (mixins under `...lumance.mixin`,
listed in `src/main/resources/lumance.mixins.json`). Add classes there, list
new mixins in the json, `gradlew build`, boot - no separate mod jar or
`mods/` juggling needed.

Two things mod authors (and you, when picking mods) should know:

- Mods must target **this exact game build**. A mod built against a different
  26.3 pre-release can fail with missing mixin targets or signature mismatches
  (early fabric-api `+26.3` builds did exactly this on 26.3-release) - that is a
  mod/game skew, not a Lumance bug. Test mods the same way: boot and read the
  Mixin section of the log.
- Access widener namespaces for unobfuscated versions are `official`, not
  `named` (`accessWidener v2 official`).

## Layout

```
patches/            every Lumance patch in one tree mirroring package layout;
                    server, client and brigadier tracks split by path (see below)
src/lumance/java/   Lumance's own classes, compiled into the server jar
src/main/java/      Lumance's built-in Fabric mod (mixins + entrypoint), always loaded
work/               gitignored pipeline state; only work/main, work/client and
                    work/brigadier (each with java/ underneath) are meant to be opened
launcher/           dependency-free bootstrap (download -> extract -> patch -> launch)
buildSrc/           the Gradle tasks driving the pipeline above
docs/               documentation
```

## Patch tracks

`patches/` holds server, client and brigadier patches side by side. The track
is derived from the path: `com/mojang/brigadier/**` is brigadier;
`net/minecraft/client/**`, `com/mojang/realmsclient/**`,
`com/mojang/blaze3d/**` and `com/mojang/renderpearl/**` are client;
everything else is server. Each `genPatches*`/`apply*` task only touches its
own track, and generating over another track's differing patch fails loudly
instead of overwriting it.
