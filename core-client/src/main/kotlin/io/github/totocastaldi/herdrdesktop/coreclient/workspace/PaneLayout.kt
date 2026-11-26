package io.github.totocastaldi.herdrdesktop.coreclient.workspace

/**
 * Edits of a tab's pane tree (REQ-WS-002), in the shape of the desktop app: splits are `row` or `column`
 * nodes whose children carry a `size.weight`. An edit of a pane that is not in the tree leaves it unchanged.
 */
object PaneLayout {
    /** Smallest share of a split a child keeps while its divider is dragged. */
    const val MIN_SHARE = 0.05

    /** Leaves of the tree, in pane order. */
    fun leaves(root: PaneNode): List<PaneNode.Leaf> = when (root) {
        is PaneNode.Leaf -> listOf(root)
        is PaneNode.Split -> root.children.flatMap(::leaves)
    }

    fun leaf(root: PaneNode, paneId: String): PaneNode.Leaf? = leaves(root).firstOrNull { it.id == paneId }

    /**
     * Splits pane [paneId] along [axis] and puts [newLeaf] right after it. Inside a split of the same axis
     * the new pane becomes a sibling and takes half of the pane's weight; otherwise the pane is replaced by
     * a new split [splitId] holding the pane and the new one with equal weights.
     */
    fun split(root: PaneNode, paneId: String, axis: Axis, newLeaf: PaneNode.Leaf, splitId: String): PaneNode =
        when (root) {
            is PaneNode.Leaf -> if (root.id == paneId) pair(root, axis, newLeaf, splitId) else root
            is PaneNode.Split -> splitIn(root, paneId, axis, newLeaf, splitId)
        }

    private fun splitIn(
        split: PaneNode.Split,
        paneId: String,
        axis: Axis,
        newLeaf: PaneNode.Leaf,
        splitId: String
    ): PaneNode {
        val index = split.children.indexOfFirst { it is PaneNode.Leaf && it.id == paneId }
        val children = split.children.toMutableList()
        val pane = split.children.getOrNull(index)
        return when {
            pane == null -> split.copy(children = split.children.map { split(it, paneId, axis, newLeaf, splitId) })

            split.axis == axis || split.children.size == 1 -> {
                val half = weightOf(pane) / 2
                children[index] = pane.withWeight(half)
                children.add(index + 1, newLeaf.withWeight(half))
                split.copy(axis = axis, children = children)
            }

            else -> {
                children[index] = pair(pane as PaneNode.Leaf, axis, newLeaf, splitId)
                split.copy(children = children)
            }
        }
    }

    /**
     * Removes pane [paneId]. A split left with one child is replaced by that child, which takes the split's
     * weight; the root split is kept even when it is left with one child or none.
     */
    fun close(root: PaneNode, paneId: String): PaneNode = when (root) {
        is PaneNode.Leaf -> root
        is PaneNode.Split -> root.copy(children = closeIn(root.children, paneId))
    }

    private fun closeIn(children: List<PaneNode>, paneId: String): List<PaneNode> = children.mapNotNull { child ->
        when (child) {
            is PaneNode.Leaf -> child.takeUnless { it.id == paneId }

            is PaneNode.Split -> {
                val rest = closeIn(child.children, paneId)
                when (rest.size) {
                    0 -> null
                    1 -> rest.single().withWeight(child.weight)
                    else -> child.copy(children = rest)
                }
            }
        }
    }

    /** Sets the weights of the children of split [splitId]; a list of the wrong length is ignored. */
    fun setWeights(root: PaneNode, splitId: String, weights: List<Double>): PaneNode = when (root) {
        is PaneNode.Leaf -> root

        is PaneNode.Split -> if (root.id == splitId && weights.size == root.children.size) {
            root.copy(children = root.children.zip(weights) { child, w -> child.withWeight(w) })
        } else {
            root.copy(children = root.children.map { setWeights(it, splitId, weights) })
        }
    }

    /** Weight of a child; a child without one counts as 1, as in the desktop app. */
    fun weightOf(node: PaneNode): Double = node.weight?.takeIf { it > 0 } ?: 1.0

    /**
     * Weights after moving the divider between child [index] and child `index + 1` by [delta], a share of
     * the whole split (positive towards the end). The two children keep their total and at least
     * [MIN_SHARE] of the split each; the other children do not change.
     */
    fun dragDivider(weights: List<Double>, index: Int, delta: Double): List<Double> {
        require(index in 0 until weights.size - 1) { "no divider $index among ${weights.size} children" }
        val total = weights.sum()
        val pair = weights[index] + weights[index + 1]
        val min = (MIN_SHARE * total).coerceAtMost(pair / 2)
        val first = (weights[index] + delta * total).coerceIn(min, pair - min)
        return weights.toMutableList().apply {
            this[index] = first
            this[index + 1] = pair - first
        }
    }

    private fun pair(pane: PaneNode.Leaf, axis: Axis, newLeaf: PaneNode.Leaf, splitId: String) =
        PaneNode.Split(splitId, axis, listOf(pane.withWeight(1.0), newLeaf.withWeight(1.0)), pane.weight)
}

private fun PaneNode.withWeight(weight: Double?): PaneNode = when (this) {
    is PaneNode.Leaf -> copy(weight = weight)
    is PaneNode.Split -> copy(weight = weight)
}

private fun PaneNode.Leaf.withWeight(weight: Double?): PaneNode.Leaf = copy(weight = weight)
