package com.nestgallery.viewer.data.face

import kotlin.math.sqrt

/**
 * Groups face embeddings into "People" clusters, similar to Google Photos.
 * Preserves user-assigned names and automatically selects the most representative
 * cover photo for each person.
 */
class FaceClusterer(
    private val similarityThreshold: Float = 0.70f
) {

    /**
     * Runs clustering on all faces in the database and updates database assignments.
     */
    fun clusterFaces(database: FaceDatabase) {
        val faces = database.getAllFacesForClustering()
        if (faces.isEmpty()) return

        // Existing people and names to preserve
        val existingPeople = database.getAllPeople().associate { it.id to it.name }
        var nextPersonId = (existingPeople.keys.maxOrNull() ?: 0L) + 1L

        // Group faces that already have a personId
        val personNameToPreserve = existingPeople.toMutableMap()
        val faceToPerson = mutableMapOf<Long, Long>()
        val personFaces = mutableMapOf<Long, MutableList<ClusterFaceItem>>()

        // Track assigned faces
        val unassignedFaces = mutableListOf<ClusterFaceItem>()

        for (face in faces) {
            if (face.currentPersonId > 0 && personNameToPreserve.containsKey(face.currentPersonId)) {
                faceToPerson[face.faceId] = face.currentPersonId
                personFaces.getOrPut(face.currentPersonId) { mutableListOf() }.add(face)
            } else {
                unassignedFaces.add(face)
            }
        }

        // Try to match unassigned faces to existing person clusters first
        val iterator = unassignedFaces.iterator()
        val remainingFaces = mutableListOf<ClusterFaceItem>()

        while (iterator.hasNext()) {
            val face = iterator.next()
            var bestPersonId: Long? = null
            var bestSim = similarityThreshold

            for ((personId, cluster) in personFaces) {
                // Calculate average similarity to cluster or max similarity
                for (clusterFace in cluster) {
                    val sim = FaceEmbeddingHelper.cosineSimilarity(face.embedding, clusterFace.embedding)
                    if (sim > bestSim) {
                        bestSim = sim
                        bestPersonId = personId
                    }
                }
            }

            if (bestPersonId != null) {
                faceToPerson[face.faceId] = bestPersonId
                personFaces.getValue(bestPersonId).add(face)
            } else {
                remainingFaces.add(face)
            }
        }

        // Now cluster remaining unassigned faces together using greedy leader clustering
        val visited = BooleanArray(remainingFaces.size)

        for (i in remainingFaces.indices) {
            if (visited[i]) continue
            visited[i] = true

            val leader = remainingFaces[i]
            val cluster = mutableListOf(leader)

            for (j in (i + 1) until remainingFaces.size) {
                if (visited[j]) continue
                val candidate = remainingFaces[j]
                val sim = FaceEmbeddingHelper.cosineSimilarity(leader.embedding, candidate.embedding)
                if (sim >= similarityThreshold) {
                    visited[j] = true
                    cluster.add(candidate)
                }
            }

            val newPersonId = nextPersonId++
            personNameToPreserve[newPersonId] = "Person $newPersonId"
            personFaces[newPersonId] = cluster

            for (member in cluster) {
                faceToPerson[member.faceId] = newPersonId
            }
        }

        // Select cover face for each person (the face closest to centroid)
        val coverFaceMap = mutableMapOf<Long, Long>()

        for ((personId, cluster) in personFaces) {
            if (cluster.isEmpty()) continue

            if (cluster.size == 1) {
                coverFaceMap[personId] = cluster[0].faceId
            } else {
                // Compute normalized centroid
                val dim = cluster[0].embedding.size
                val centroid = FloatArray(dim)
                for (face in cluster) {
                    for (k in 0 until dim) {
                        centroid[k] += face.embedding[k]
                    }
                }
                var norm = 0f
                for (k in 0 until dim) {
                    centroid[k] /= cluster.size
                    norm += centroid[k] * centroid[k]
                }
                norm = sqrt(norm.toDouble()).toFloat()
                if (norm > 0) {
                    for (k in 0 until dim) centroid[k] /= norm
                }

                // Pick face with highest similarity to centroid
                var bestFaceId = cluster[0].faceId
                var highestSim = -1f
                for (face in cluster) {
                    val sim = FaceEmbeddingHelper.cosineSimilarity(face.embedding, centroid)
                    if (sim > highestSim) {
                        highestSim = sim
                        bestFaceId = face.faceId
                    }
                }
                coverFaceMap[personId] = bestFaceId
            }
        }

        // Apply everything to DB in single transaction
        database.applyClusterAssignments(
            personNames = personNameToPreserve,
            faceToPersonMap = faceToPerson,
            personCoverFaceMap = coverFaceMap
        )
    }
}
