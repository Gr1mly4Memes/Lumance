# Patches

Every Lumance patch lives in this one folder, mirroring Java package layout,
e.g.:

```
patches/net/minecraft/server/MinecraftServer.java.patch
```

Patches are DiffPatch-format (same engine NeoForm uses), applied fuzzily. Each
patch belongs to exactly one track by path:

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
