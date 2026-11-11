package io.github.misdocumeno.infra.database

import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

data class LastSessionPose(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)

class ExposedLastSessionRepository {
    fun upsert(uuid: UUID, server: String, pose: LastSessionPose) {
        val now = System.currentTimeMillis()
        val id = uuid.toString()
        transaction {
            val updated =
                LastSessionTable.update({ LastSessionTable.uuid eq id }) {
                    it[LastSessionTable.server] = server
                    it[world] = pose.world
                    it[x] = pose.x
                    it[y] = pose.y
                    it[z] = pose.z
                    it[yaw] = pose.yaw
                    it[pitch] = pose.pitch
                    it[updatedAt] = now
                }
            if (updated == 0) {
                LastSessionTable.insertIgnore {
                    it[LastSessionTable.uuid] = id
                    it[LastSessionTable.server] = server
                    it[world] = pose.world
                    it[x] = pose.x
                    it[y] = pose.y
                    it[z] = pose.z
                    it[yaw] = pose.yaw
                    it[pitch] = pose.pitch
                    it[updatedAt] = now
                }
                // Race: another writer inserted between update and insertIgnore — update again.
                LastSessionTable.update({ LastSessionTable.uuid eq id }) {
                    it[LastSessionTable.server] = server
                    it[world] = pose.world
                    it[x] = pose.x
                    it[y] = pose.y
                    it[z] = pose.z
                    it[yaw] = pose.yaw
                    it[pitch] = pose.pitch
                    it[updatedAt] = now
                }
            }
        }
    }
}
