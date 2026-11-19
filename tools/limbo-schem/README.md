# limbo-schem

`trim.py` crops a Sponge v3 `.schem` to its non-air bounds before it is baked
into `velocity-limbo`.

## Why

LimboAPI 1.2.0's `VirtualWorld` has exactly 16 chunk sections (`y 0..255`), and
its WorldEdit reader calls `setBlock` for **every** cell of the file, air
included. A save of a selection that was expanded upwards past y=255 therefore
kills proxy startup with:

```
ArrayIndexOutOfBoundsException: Index 16 out of bounds for length 16
    at net.elytrium.limboapi.server.world.chunk.SimpleChunk.getSection
    at net.elytrium.limboapi.server.world.chunk.SimpleChunk.setBlock
    at net.elytrium.limboapi.file.WorldEditSchemFile.toWorld
```

The smoke gate catches it (`Couldn't pass ProxyInitializeEvent to limbo`), it is
not a silent failure. Cropping also cuts the startup decode: the untrimmed hub
was 29.9 M varints, trimmed it is 3.96 M.

## Baking a new hub build

1. In a scratch creative world on 26.3: select the build (`//wand`, `//copy`),
   `//schem save misplease_hub` (FAWE writes it under
   `plugins/FastAsyncWorldEdit/schematics/<uuid>/`).
2. Copy the file out and trim it:
   ```bash
   python tools/limbo-schem/trim.py misplease_hub.schem \
     plugins/velocity-limbo/src/main/resources/limbo/hub.schem
   ```
   It prints the `toWorld(...)` offset for the new file.
3. Update in `LimboWorlds.kt`: `PASTE_X/Y/Z` from that line, `SPAWN_Y` (the gray
   carpet's world y + 1) and `FALL_Y` if the build's lowest block changed, plus
   the `carpet block is …` log coordinate in `LimboBootstrap.kt`.
4. Check the view distance covers the build from the carpet: the tool prints the
   volume, but the chunk spread comes from the block data — the login hub reaches
   8 chunks from its carpet, hence `setViewDistance(8)`.

The same `.schem` can be pasted into the real hub world (`newspawn`) with
WorldEdit; the trim does not change the build's geometry, only its bounds.

## Geometry as of the current file

- source: 321x290x321, `Offset [-160,-129,-160]`, carpet at world `(0,65,0)`
- trimmed: 196x111x182, non-air world `x -102..93`, `y 10..120`, `z -40..141`
- paste: `(-102, 10, -40)` → carpet back on `(0,65,0)`, spawn `(0.5, 66, 0.5)`

Block entities (84 in the source: beacon levels, sign text) are kept and their
positions are shifted with the crop; LimboAPI does apply them.
