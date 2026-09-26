# Patches

Every Lumance patch lives in this one folder, mirroring Java package layout,
e.g.:

```
patches/net/minecraft/server/MinecraftServer.java.patch
```

Patches are DiffPatch-format (same engine NeoForm uses), applied exactly, with
an opt-in fuzzy pass (`fuzzyApplyPatches` and friends) for hand-dropped files.
Each patch belongs to exactly one track by path:

- `com/mojang/brigadier/**` → brigadier track (`work/brigadier/java`)
- `net/minecraft/client/**`, `com/mojang/realmsclient/**`,
  `com/mojang/blaze3d/**`, `com/mojang/renderpearl/**` → client track
  (`work/client/java`)
- everything else → server track (`work/main/java`)

Workflows (one per track, same shape):

```bash
gradlew setup          # decompile everything, apply patches -> work/main|client|brigadier/java
# ... edit files under work/main/java ...
gradlew genPatches      # regenerate server-track patches from your edits
gradlew genPatchesClient      # same for work/client/java
gradlew genPatchesBrigadier   # same for work/brigadier/java
gradlew build           # compile + repack + build the bootstrap jar
```

Dropping in a patch by hand (PaperMC-style) also works: put the file in this
tree, then run `setup` (or the matching `apply*` task) for the strict pass.
Three outcomes:

- Applies exactly → silent, like any other patch.
- Drifted but salvageable → strict `setup` fails loudly on that file. Run the
  matching `fuzzyApply*` task (`fuzzyApplyPatches`, `fuzzyApplyClientPatches`,
  `fuzzyApplyBrigadierPatches`) for one fuzzy attempt per file; anything it
  catches is named loudly in the log. Run the matching `genPatches*` right
  after to rebase into exact patches.
- Half-placeable (some hunks anchor on code that only exists upstream, e.g.
  Paper-only methods) → the fuzzy tasks apply what lands and park the rest in
  `work/rejects/`, mirroring the source layout
  (`work/rejects/net/minecraft/commands/Commands.java.rej`). Hand-port those
  hunks, then `genPatches*` to fold everything into exact patches.
- Truly conflicting → the build fails on that file instead of corrupting it,
  fuzzy or not.

Drop-ins must use DiffPatch's native format - the same shape `genPatches`
emits, e.g.:

```
--- a/net/minecraft/server/Foo.java
+++ b/net/minecraft/server/Foo.java
@@ -10,5 +_,5 @@
     context line
-    old line
+    new line // Lumance
     context line
```

Plain `git diff` output is *not* understood. Easiest reliable route to that
shape: make the edit in `work/`, run `genPatches*`, keep the file it writes.
(A dropped file missing the `.java` part, e.g. `AdvancementHolder.patch`,
is resolved to its `.java` target automatically; anything else fails loudly
with the expected path.)

Patches are the source of truth:

- Edit sources, run the matching `genPatches*`, and the patch is (re)created.
- Delete a patch file and run `genPatches*`, and that change is reverted in
  its tree instead of coming back. (Re-edit the file afterwards
  if you only meant to redo it.)
- If you revert an edit by hand, the next `genPatches*` drops its patch as stale.
- A file edited in two trees with different content fails loudly instead of
  silently overwriting the other track's patch - pick one, copy it over, rerun.

`work/` is never committed - only this folder (plus `src/lumance/java`) defines the fork.
Run `gradlew setup -PrefreshSources` to throw away local edits and start clean.
