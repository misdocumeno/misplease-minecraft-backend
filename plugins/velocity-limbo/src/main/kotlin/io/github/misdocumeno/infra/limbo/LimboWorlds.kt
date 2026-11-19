package io.github.misdocumeno.infra.limbo

import net.elytrium.limboapi.api.LimboFactory
import net.elytrium.limboapi.api.chunk.Dimension
import net.elytrium.limboapi.api.chunk.VirtualWorld
import net.elytrium.limboapi.api.file.BuiltInWorldFileType

/**
 * The login limbo world: the hub build, loaded from `limbo/hub.schem`.
 *
 * The schematic is a git-tracked build input baked into the plugin jar (the CI
 * compiles it from source; nothing is mounted on the VM). To replace the build:
 * WorldEdit `//schem save misplease_hub` on a scratch world, then copy the file
 * here.
 *
 * Coordinate notes (Sponge v3, 321x290x321, `Offset [-160,-129,-160]`):
 * - the paste origin puts the gray carpet, local `(160,129,160)`, on world
 *   `(0,129,0)`, which is why the spawn is `(0.5, 130, 0.5)`;
 * - LimboAPI's reader ignores `Offset`/`Metadata` and only reads the block data,
 *   so the offsets below are ours to keep in sync with the saved file.
 */
object LimboWorlds {

    /** Where the schematic is pasted; makes the gray carpet land on world (0,129,0). */
    private const val PASTE_X = -160
    private const val PASTE_Y = 0
    private const val PASTE_Z = -160

    const val SPAWN_X = 0.5
    const val SPAWN_Y = 130.0
    const val SPAWN_Z = 0.5

    /** Below this a player is falling out of the build; put them back on the carpet. */
    const val FALL_Y = 60.0

    fun hub(factory: LimboFactory): VirtualWorld {
        val stream = javaClass.getResourceAsStream("/limbo/hub.schem")
            ?: error("limbo/hub.schem is missing from the plugin jar; the limbo has no world to show")

        val file = stream.use {
            factory.openWorldFile(BuiltInWorldFileType.WORLDEDIT_SCHEM, it)
        }

        val world = factory.createVirtualWorld(
            Dimension.OVERWORLD,
            SPAWN_X,
            SPAWN_Y,
            SPAWN_Z,
            0.0f,
            0.0f,
        )
        file.toWorld(factory, world, PASTE_X, PASTE_Y, PASTE_Z)

        world.fillSkyLight(15)
        // Sky light alone leaves the build near-black on 26.x: the dimension type
        // carries its own light attributes, so fill block light as well.
        world.fillBlockLight(15)
        return world
    }
}
