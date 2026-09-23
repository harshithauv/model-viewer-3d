package com.infusory.modelviewer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One labelled part of a glTF model: a node that carries an
 * `extras.prop` string, plus that node's **model-space** position
 * (i.e. already walked up through every ancestor's translation /
 * rotation / scale - see [GlbLabelParser]).
 */
data class NodeLabel(
    val nodeName: String,
    val text: String,
    val localTranslation: FloatArray // x, y, z, already in model-root space
)

/**
 * Minimal GLB (binary glTF) reader.
 *
 * A .glb file is: a 12-byte header (magic "glTF", version, total length)
 * followed by one or more chunks. The very first chunk is always the
 * JSON chunk (chunk type 0x4E4F534A = "JSON"), which is all we need here
 * - we never touch the binary buffer chunk (geometry/animation data),
 * so this stays cheap even for large files.
 *
 * We look at every node's `extras.prop` field. If present, that string
 * is the label to draw for that node.
 *
 * A node's `translation` in glTF is relative to its *parent*, not the model
 * root - so a labelled node buried inside a hierarchy (e.g. a planet's
 * label parented under that planet's mesh, itself offset from the root by
 * an orbital radius) would otherwise place every label near the origin. We
 * walk from each labelled node up to its scene root, accumulating every
 * ancestor's translation/rotation/scale (both TRS-triplet and raw-`matrix`
 * nodes are supported), to get that node's true position in root space.
 */
object GlbLabelParser {

    private const val GLB_MAGIC = 0x46546C67 // "glTF" little-endian
    private const val CHUNK_TYPE_JSON = 0x4E4F534A // "JSON" little-endian

    fun parseLabels(context: Context, assetPath: String): List<NodeLabel> {
        return try {
            val input = context.assets.open(assetPath)
            val bytes: ByteArray
            try {
                val outputBuffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    outputBuffer.write(chunk, 0, read)
                }
                bytes = outputBuffer.toByteArray()
            } finally {
                input.close()
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = buffer.int
            if (magic != GLB_MAGIC) return emptyList() // not a binary glTF, nothing to parse

            buffer.int // version - unused
            buffer.int // total file length - unused

            val chunkLength = buffer.int
            val chunkType = buffer.int
            if (chunkType != CHUNK_TYPE_JSON) return emptyList()

            val jsonBytes = ByteArray(chunkLength)
            buffer.get(jsonBytes)
            val json = String(jsonBytes, Charsets.UTF_8)

            extractLabels(JSONObject(json))
        } catch (e: Exception) {
            // A malformed/unreadable glb should never crash model loading -
            // the model still renders, it just won't have labels.
            emptyList()
        }
    }
    private fun extractLabels(root: JSONObject): List<NodeLabel> {
        val nodesArray: JSONArray = root.optJSONArray("nodes") ?: return emptyList()
        val nodeCount = nodesArray.length()
        val nodes = Array(nodeCount) { nodesArray.optJSONObject(it) ?: JSONObject() }

        // parent[i] = index of i's parent node, or -1 for a scene root.
        val parent = IntArray(nodeCount) { -1 }
        for (i in 0 until nodeCount) {
            val children = nodes[i].optJSONArray("children") ?: continue
            for (c in 0 until children.length()) {
                val childIndex = children.optInt(c, -1)
                if (childIndex in 0 until nodeCount) parent[childIndex] = i
            }
        }

        val result = mutableListOf<NodeLabel>()
        for (i in 0 until nodeCount) {
            val node = nodes[i]
            val extras = node.optJSONObject("extras") ?: continue
            val label = extras.optString("prop", "")
            if (label.isBlank()) continue

            result.add(
                NodeLabel(
                    nodeName = node.optString("name", "node_$i"),
                    text = label,
                    localTranslation = modelSpacePosition(nodes, parent, i)
                )
            )
        }
        return result
    }

    /**
     * Position of node [index]'s own origin, expressed in the coordinate
     * space of its ultimate scene-root ancestor. Starts from the node's
     * own translation (its position within its parent) and walks upward,
     * applying each ancestor's scale, rotation and translation in turn -
     * the standard child->parent point transform used throughout glTF/3D
     * engines. A `matrix` node (the spec's other allowed form) is applied
     * as a direct 4x4 transform instead of separate T/R/S.
     */
    private fun modelSpacePosition(nodes: Array<JSONObject>, parent: IntArray, index: Int): FloatArray {
        var p = readTranslation(nodes[index])
        var current = parent[index]
        var guard = 0 // cheap cycle guard - a well-formed glTF has no cycles
        while (current != -1 && guard < nodes.size) {
            val ancestor = nodes[current]
            val matrix = ancestor.optJSONArray("matrix")
            p = if (matrix != null && matrix.length() == 16) {
                applyMatrix(matrix, p)
            } else {
                val scale = readScale(ancestor)
                val scaled = floatArrayOf(p[0] * scale[0], p[1] * scale[1], p[2] * scale[2])
                val rotated = readRotation(ancestor)?.let { rotateByQuaternion(it, scaled) } ?: scaled
                val t = readTranslation(ancestor)
                floatArrayOf(rotated[0] + t[0], rotated[1] + t[1], rotated[2] + t[2])
            }
            current = parent[current]
            guard++
        }
        return p
    }

    private fun readTranslation(node: JSONObject): FloatArray {
        val t = node.optJSONArray("translation") ?: return floatArrayOf(0f, 0f, 0f)
        return floatArrayOf(
            t.optDouble(0, 0.0).toFloat(),
            t.optDouble(1, 0.0).toFloat(),
            t.optDouble(2, 0.0).toFloat()
        )
    }

    private fun readScale(node: JSONObject): FloatArray {
        val s = node.optJSONArray("scale") ?: return floatArrayOf(1f, 1f, 1f)
        return floatArrayOf(
            s.optDouble(0, 1.0).toFloat(),
            s.optDouble(1, 1.0).toFloat(),
            s.optDouble(2, 1.0).toFloat()
        )
    }

    /** glTF quaternions are stored [x, y, z, w]. Returns null if absent (= identity). */
    private fun readRotation(node: JSONObject): FloatArray? {
        val r = node.optJSONArray("rotation") ?: return null
        return floatArrayOf(
            r.optDouble(0, 0.0).toFloat(),
            r.optDouble(1, 0.0).toFloat(),
            r.optDouble(2, 0.0).toFloat(),
            r.optDouble(3, 1.0).toFloat()
        )
    }

    /** Standard quaternion * vector rotation: v' = v + 2w(q x v) + 2(q x (q x v)). */
    private fun rotateByQuaternion(q: FloatArray, v: FloatArray): FloatArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val tx = 2f * (qy * v[2] - qz * v[1])
        val ty = 2f * (qz * v[0] - qx * v[2])
        val tz = 2f * (qx * v[1] - qy * v[0])
        val cx = qy * tz - qz * ty
        val cy = qz * tx - qx * tz
        val cz = qx * ty - qy * tx
        return floatArrayOf(
            v[0] + qw * tx + cx,
            v[1] + qw * ty + cy,
            v[2] + qw * tz + cz
        )
    }

    /** Column-major 4x4, per the glTF spec, applied to a point (w = 1). */
    private fun applyMatrix(m: JSONArray, v: FloatArray): FloatArray {
        val e = FloatArray(16) { m.optDouble(it, 0.0).toFloat() }
        val x = v[0]; val y = v[1]; val z = v[2]
        return floatArrayOf(
            e[0] * x + e[4] * y + e[8] * z + e[12],
            e[1] * x + e[5] * y + e[9] * z + e[13],
            e[2] * x + e[6] * y + e[10] * z + e[14]
        )
    }
}
