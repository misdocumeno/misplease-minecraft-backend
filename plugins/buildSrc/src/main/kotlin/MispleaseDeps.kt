/**
 * Platform APIs used by convention plugins.
 * Keep in sync with [versions] paper-api / velocity-api in gradle/libs.versions.toml
 * (buildSrc convention scripts cannot use the root version catalog accessors).
 */
object MispleaseDeps {
    const val PAPER_API = "io.papermc.paper:paper-api:26.3.build.+"
    const val VELOCITY_API = "com.velocitypowered:velocity-api:4.3.0"
}
