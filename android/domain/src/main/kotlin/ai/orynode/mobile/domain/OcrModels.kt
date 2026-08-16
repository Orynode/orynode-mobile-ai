package ai.orynode.mobile.domain

data class OcrNormalizedRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val midX: Double get() = x + width / 2
    val midY: Double get() = y + height / 2
    val maxX: Double get() = x + width
    val maxY: Double get() = y + height

    fun union(other: OcrNormalizedRect): OcrNormalizedRect {
        val minX = minOf(x, other.x)
        val minY = minOf(y, other.y)
        val maxX = maxOf(this.maxX, other.maxX)
        val maxY = maxOf(this.maxY, other.maxY)
        return OcrNormalizedRect(minX, minY, maxX - minX, maxY - minY)
    }
}

data class OcrObservation(
    val text: String,
    val boundingBox: OcrNormalizedRect,
)

data class OcrLine(
    val id: Int,
    val observations: List<OcrObservation>,
) {
    val text: String
        get() = observations.joinToString(" ") { it.text }

    val boundingBox: OcrNormalizedRect
        get() {
            val first = observations.firstOrNull()?.boundingBox
                ?: return OcrNormalizedRect(0.0, 0.0, 0.0, 0.0)
            return observations.drop(1).fold(first) { acc, observation ->
                acc.union(observation.boundingBox)
            }
        }
}

data class OcrDocument(
    val observations: List<OcrObservation>,
    val lines: List<OcrLine>,
) {
    val plainText: String
        get() = lines.joinToString("\n") { it.text }

    companion object {
        fun fromObservations(
            observations: List<OcrObservation>,
            lines: List<OcrLine>? = null,
        ): OcrDocument {
            val cleaned = observations
                .map { it.copy(text = it.text.trim()) }
                .filter { it.text.isNotEmpty() }
            return OcrDocument(
                observations = cleaned,
                lines = lines ?: clusterLines(cleaned),
            )
        }

        private fun clusterLines(observations: List<OcrObservation>): List<OcrLine> {
            val sorted = observations.sortedWith { a, b ->
                if (kotlin.math.abs(a.boundingBox.midY - b.boundingBox.midY) > 0.02) {
                    b.boundingBox.midY.compareTo(a.boundingBox.midY)
                } else {
                    a.boundingBox.midX.compareTo(b.boundingBox.midX)
                }
            }
            val groups = mutableListOf<MutableList<OcrObservation>>()
            for (observation in sorted) {
                val last = groups.lastOrNull()
                val reference = last?.firstOrNull()
                if (last != null &&
                    reference != null &&
                    kotlin.math.abs(reference.boundingBox.midY - observation.boundingBox.midY) <= 0.03
                ) {
                    last.add(observation)
                } else {
                    groups.add(mutableListOf(observation))
                }
            }
            return groups.mapIndexed { index, group ->
                OcrLine(
                    id = index,
                    observations = group.sortedBy { it.boundingBox.midX },
                )
            }
        }
    }
}
