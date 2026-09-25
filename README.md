# Lumance

A custom, lightweight Minecraft server you patch yourself. Just the vanilla
sources, your patches, and a server that runs them — with Fabric Loader
underneath, so Fabric mods (mixins, entrypoints, access wideners) work too.
A hobby playroom for messing with the game.

```bash
gradlew setup       # download + decompile Minecraft into work/main/java
# ... edit the sources ...
gradlew genPatches   # save your edits as patches
gradlew build        # build the server jar
java -jar build/dist/lumance-*-server.jar nogui
```

See [docs/workflow.md](docs/workflow.md) for the full pipeline, and
[patches/README.md](patches/README.md) for the patch workflow.

The client gets the same treatment (`work/client/java`,
`gradlew genPatchesClient`, compiled into `build/patched/lumance-client.jar`
as a sanity check), and so does Mojang's brigadier library
(`work/brigadier/java`, `gradlew genPatchesBrigadier`, compiled
into both jars). Running the patched client (assets, natives, auth) is out
of scope — this playroom is server-first.

Fabric API ships built in, and Lumance itself is a built-in mod
(`src/main/java` — add your own mixins and entrypoints there). Drop extra mod
jars into `mods/` like a stock Fabric server; see the Fabric section in
[docs/workflow.md](docs/workflow.md).

## License

Lumance's own code is GPL-3.0-only — see [LICENSE](LICENSE). The `patches/`
you write are yours. (Mojang's game code itself is only ever downloaded from
Mojang at build/run time, never redistributed.)
