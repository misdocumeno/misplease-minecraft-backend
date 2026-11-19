package io.github.misdocumeno.infra.limbo

import net.elytrium.limboapi.api.LimboFactory
import net.elytrium.limboapi.api.chunk.Dimension
import net.elytrium.limboapi.api.chunk.VirtualWorld

/**
 * The placeholder limbo world: a small platform in the void.
 *
 * Deliberately built in code instead of loading a schematic, so there is no
 * third-party build to license or ship. When the real lobby exists, replace this
 * with `factory.openWorldFile(BuiltInWorldFileType.WORLDEDIT_SCHEM, path)` and
 * call `toWorld(factory, world, 0, 0, 0)`.
 */
object LimboWorlds {

    private const val RADIUS = 4
    const val FLOOR_Y = 64

    fun platform(factory: LimboFactory): VirtualWorld {
        val world = factory.createVirtualWorld(
            Dimension.OVERWORLD,
            0.5,
            (FLOOR_Y + 1).toDouble(),
            0.5,
            0.0f,
            0.0f,
        )

        val floor = factory.createSimpleBlock("minecraft:quartz_block")
        val trim = factory.createSimpleBlock("minecraft:smooth_quartz")

        for (x in -RADIUS..RADIUS) {
            for (z in -RADIUS..RADIUS) {
                val edge = x == -RADIUS || x == RADIUS || z == -RADIUS || z == RADIUS
                world.setBlock(x, FLOOR_Y, z, if (edge) trim else floor)
            }
        }

        world.fillSkyLight(15)
        return world
    }
}
