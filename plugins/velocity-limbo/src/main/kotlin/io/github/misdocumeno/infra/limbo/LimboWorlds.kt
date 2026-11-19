package io.github.misdocumeno.infra.limbo

import net.elytrium.limboapi.api.LimboFactory
import net.elytrium.limboapi.api.chunk.Dimension
import net.elytrium.limboapi.api.chunk.VirtualWorld
import net.elytrium.limboapi.api.file.BuiltInWorldFileType

/**
 * The login limbo world: the hub build, loaded from `limbo/hub.schem`.
 *
 * The schematic is a git-tracked build input baked into the plugin jar (CI
 * compiles it from source; nothing is mounted on the VM).
 *
 * Two limits of LimboAPI 1.2.0 shape this file, see `tools/limbo-schem/README.md`:
 * - a `VirtualWorld` only has 16 chunk sections, i.e. y 0..255, and the reader
 *   calls `setBlock` for every cell including air, so the schematic is trimmed
 *   to its non-air bounds (196x111x182);
 * - block entities (beacon levels, sign text) are honoured only for the blocks
 *   that survive that trim.
 *
 * Geometry: the trim keeps the original world layout, so pasting at
 * `(-102, 10, -40)` puts the gray carpet, local `(102,55,40)`, back on
 * `(0,65,0)` - the same place it has in the source world.
 */
object LimboWorlds {

    private const val PASTE_X = -102
    private const val PASTE_Y = 10
    private const val PASTE_Z = -40

    const val SPAWN_X = 0.5
    const val SPAWN_Y = 66.0
    const val SPAWN_Z = 0.5

    /** Nothing in the build sits below y=10, so this is already clear of it. */
    const val FALL_Y = 8.0

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
