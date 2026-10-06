package com.nestgallery.viewer.data.face

import kotlin.math.max
import kotlin.math.sqrt

/**
 * High-accuracy Agglomerative Face Clusterer using Average + Max-Pair Linkage and Centroid updates.
 * Specifically designed to solve:
 * 1. Cluster fragmentation (prevents splitting the same face into Person 1, Person 55, Person 66).
 * 2. Age progression invariance (bridges appearance changes across time).
 * 3. Preserves user-assigned names and automatically selects the most representative cover face.
 */
class FaceClusterer(
    private val similarityThreshold: Float = 0.62f // Calibrated for FaceNet-512 normalized embeddings
) {

    private class Cluster(
        var id: Long,
        var name: String,
        val faces: MutableList<ClusterFaceItem> = mutableListOf()
    ) {
        val centroid: FloatArray = FloatArray(512)

        fun updateCentroid() {
            if (faces.isEmpty()) return
            centroid.fill(0f)
            val dim = faces[0].embedding.size
            for (face in faces) {
                for (k in 0 until dim) {
                    centroid[k] += face.embedding[k]
                }
            }
            var sumSq = 0f
            for (k in 0 until dim) {
                centroid[k] /= faces.size
                sumSq += centroid[k] * centroid[k]
            }
            val norm = sqrt(sumSq.toDouble()).toFloat()
            if (norm > 0f) {
                for (k in 0 until dim) centroid[k] /= norm
            }
        }

        fun bestCoverFaceId(): Long {
            if (faces.isEmpty()) return 0L
            if (faces.size == 1) return faces[0].faceId

            // Pick face closest to centroid (canonical, well-lit face)
            var bestId = faces[0].faceId
            var highestSim = -1f
            for (f in faces) {
                val sim = FaceEmbeddingHelper.cosineSimilarity(f.embedding, centroid)
                if (sim > highestSim) {
                    highestSim = sim
                    bestId = f.faceId
                }
            }
            return bestId
        }
    }

    /**
     * Clusters faces, optionally scoped to a folder tree.
     */
    fun clusterFaces(database: FaceDatabase, folderPath: String? = null) {
        val faces = database.getFacesForClustering(folderPath)
        if (faces.isEmpty()) return

        val existingPeople = database.getAllPeople().associate { it.id to it.name }
        var nextPersonId = (existingPeople.keys.maxOrNull() ?: 0L) + 1L

        // Initial setup: group faces that already have a recognized person ID
        val clusters = mutableListOf<Cluster>()
        val unassignedFaces = mutableListOf<ClusterFaceItem>()

        val existingClusterMap = mutableMapOf<Long, Cluster>()

        for (face in faces) {
            if (face.currentPersonId > 0 && existingPeople.containsKey(face.currentPersonId)) {
                val cluster = existingClusterMap.getOrPut(face.currentPersonId) {
                    Cluster(face.currentPersonId, existingPeople[face.currentPersonId] ?: "Person ${face.currentPersonId}").also {
                        clusters.add(it)
                    }
                }
                cluster.faces.add(face)
            } else {
                unassignedFaces.add(face)
            }
        }

        // Compute centroids for existing clusters
        for (c in clusters) {
            c.updateCentroid()
        }

        // Start each unassigned face in its own mini-cluster
        for (face in unassignedFaces) {
            val c = Cluster(nextPersonId++, "Person $nextPersonId")
            c.faces.add(face)
            c.updateCentroid()
            clusters.add(c)
        }

        // Agglomerative Hierarchical Clustering with Centroid & Average Linkage
        var merged = true
        while (merged && clusters.size > 1) {
            merged = false
            var bestI = -1
            var bestJ = -1
            var highestSim = similarityThreshold

            for (i in 0 until clusters.size) {
                val c1 = clusters[i]
                for (j in (i + 1) until clusters.size) {
                    val c2 = clusters[j]

                    val sim = computeClusterSimilarity(c1, c2)
                    if (sim > highestSim) {
                        highestSim = sim
                        bestI = i
                        bestJ = j
                        merged = true
                    }
                }
            }

            if (merged && bestI != -1 && bestJ != -1) {
                val target = clusters[bestI]
                val source = clusters[bestJ]

                // Merge source into target
                target.faces.addAll(source.faces)
                target.updateCentroid()

                // Preserve custom name if source had one and target didn't
                if (!source.name.startsWith("Person ") && target.name.startsWith("Person ")) {
                    target.name = source.name
                }

                clusters.removeAt(bestJ)
            }
        }

        // Build assignments to write back to SQLite
        val personNames = mutableMapOf<Long, String>()
        val faceToPersonMap = mutableMapOf<Long, Long>()
        val personCoverFaceMap = mutableMapOf<Long, Long>()

        for (c in clusters) {
            if (c.faces.isEmpty()) continue
            personNames[c.id] = c.name
            personCoverFaceMap[c.id] = c.bestCoverFaceId()

            for (face in c.faces) {
                faceToPersonMap[face.faceId] = c.id
            }
        }

        database.applyClusterAssignments(
            personNames = personNames,
            faceToPersonMap = faceToPersonMap,
            personCoverFaceMap = personCoverFaceMap
        )
    }

    /**
     * Computes similarity between two clusters:
     * Combines centroid similarity (overall facial structure) and max pair similarity
     * (bridges age progression and angle variations between closest matching photos).
     */
    private fun computeClusterSimilarity(c1: Cluster, c2: Cluster): Float {
        val centroidSim = FaceEmbeddingHelper.cosineSimilarity(c1.centroid, c2.centroid)

        var maxPairSim = -1f
        var sumPairSim = 0f
        var pairCount = 0

        for (f1 in c1.faces) {
            for (f2 in c2.faces) {
                val sim = FaceEmbeddingHelper.cosineSimilarity(f1.embedding, f2.embedding)
                if (sim > maxPairSim) maxPairSim = sim
                sumPairSim += sim
                pairCount++
            }
        }

        val avgPairSim = if (pairCount > 0) sumPairSim / pairCount else centroidSim

        // Composite metric: 50% centroid match + 30% average match + 20% closest-pair bridge
        return 0.50f * centroidSim + 0.30f * avgPairSim + 0.20f * maxPairSim
    }
}
