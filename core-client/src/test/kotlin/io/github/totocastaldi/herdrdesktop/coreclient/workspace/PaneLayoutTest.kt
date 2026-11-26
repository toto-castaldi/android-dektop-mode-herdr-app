package io.github.totocastaldi.herdrdesktop.coreclient.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PaneLayoutTest {
    private fun leaf(id: String, weight: Double? = 1.0) = PaneNode.Leaf(id, "t-$id", weight)

    /** A new tab: a row with the tab's id and one leaf of weight 1. */
    private val single = PaneNode.Split("tb1", Axis.ROW, listOf(leaf("p1")))

    @Test
    fun `splitting the only pane of a tab places the new pane beside it with equal weights`() {
        val root = PaneLayout.split(single, "p1", Axis.ROW, leaf("p2", null), "p9") as PaneNode.Split

        assertEquals(Axis.ROW, root.axis)
        assertEquals(listOf("p1", "p2"), root.children.map { it.id })
        assertEquals(listOf(0.5, 0.5), root.children.map { it.weight })
    }

    @Test
    fun `the only pane split vertically turns the root into a column`() {
        val root = PaneLayout.split(single, "p1", Axis.COLUMN, leaf("p2"), "p9") as PaneNode.Split

        assertEquals(Axis.COLUMN, root.axis)
        assertEquals("tb1", root.id)
        assertEquals(listOf("p1", "p2"), PaneLayout.leaves(root).map { it.id })
    }

    @Test
    fun `splitting across the parent axis nests a new split in place of the pane`() {
        val row = PaneNode.Split("tb1", Axis.ROW, listOf(leaf("p1", 2.0), leaf("p2", 1.0)))

        val root = PaneLayout.split(row, "p2", Axis.COLUMN, leaf("p3"), "p9") as PaneNode.Split

        val nested = root.children[1] as PaneNode.Split
        assertEquals("p9", nested.id)
        assertEquals(Axis.COLUMN, nested.axis)
        assertEquals(1.0, nested.weight)
        assertEquals(listOf("p2", "p3"), nested.children.map { it.id })
        assertEquals(listOf(1.0, 1.0), nested.children.map { it.weight })
        assertEquals(2.0, root.children[0].weight)
    }

    @Test
    fun `splitting along the parent axis halves the pane's weight and keeps the others`() {
        val row = PaneNode.Split("tb1", Axis.ROW, listOf(leaf("p1", 2.0), leaf("p2", 1.0)))

        val root = PaneLayout.split(row, "p1", Axis.ROW, leaf("p3"), "p9") as PaneNode.Split

        assertEquals(listOf("p1", "p3", "p2"), root.children.map { it.id })
        assertEquals(listOf(1.0, 1.0, 1.0), root.children.map { it.weight })
    }

    @Test
    fun `closing a pane of a two-pane split collapses the split into the other pane with its weight`() {
        val nested = PaneNode.Split("p9", Axis.COLUMN, listOf(leaf("p2"), leaf("p3")), weight = 3.0)
        val row = PaneNode.Split("tb1", Axis.ROW, listOf(leaf("p1"), nested))

        val root = PaneLayout.close(row, "p3") as PaneNode.Split

        assertEquals(listOf("p1", "p2"), root.children.map { it.id })
        assertEquals(3.0, root.children[1].weight)
        assertEquals(PaneNode.Leaf::class, root.children[1]::class)
    }

    @Test
    fun `closing keeps the root split and the order of the other panes`() {
        val row = PaneNode.Split("tb1", Axis.ROW, listOf(leaf("p1"), leaf("p2"), leaf("p3")))

        val root = PaneLayout.close(row, "p2") as PaneNode.Split

        assertEquals("tb1", root.id)
        assertEquals(listOf("p1", "p3"), root.children.map { it.id })
    }

    @Test
    fun `editing a pane that is not in the tree changes nothing`() {
        assertEquals(single, PaneLayout.close(single, "nope"))
        assertEquals(single, PaneLayout.split(single, "nope", Axis.ROW, leaf("p2"), "p9"))
        assertSame(null, PaneLayout.leaf(single, "nope"))
    }

    @Test
    fun `weights are set on the named split only and a wrong count is ignored`() {
        val nested = PaneNode.Split("p9", Axis.COLUMN, listOf(leaf("p2"), leaf("p3")))
        val row = PaneNode.Split("tb1", Axis.ROW, listOf(leaf("p1"), nested))

        val root = PaneLayout.setWeights(row, "p9", listOf(0.25, 0.75)) as PaneNode.Split

        assertEquals(listOf(0.25, 0.75), (root.children[1] as PaneNode.Split).children.map { it.weight })
        assertEquals(1.0, root.children[0].weight)
        assertEquals(row, PaneLayout.setWeights(row, "p9", listOf(1.0)))
    }

    @Test
    fun `dragging a divider moves weight between its two panes only`() {
        val weights = PaneLayout.dragDivider(listOf(1.0, 1.0, 2.0), 0, 0.125)

        assertEquals(listOf(1.5, 0.5, 2.0), weights)
    }

    @Test
    fun `a divider cannot shrink a pane below the minimum share`() {
        val towardsEnd = PaneLayout.dragDivider(listOf(1.0, 1.0), 0, 2.0)
        val towardsStart = PaneLayout.dragDivider(listOf(1.0, 1.0), 0, -2.0)

        assertEquals(listOf(1.9, 0.1), towardsEnd.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(listOf(0.1, 1.9), towardsStart.map { Math.round(it * 1000) / 1000.0 })
    }

    @Test
    fun `a child without weight counts as one`() {
        assertEquals(1.0, PaneLayout.weightOf(leaf("p1", null)), 0.0)
        assertEquals(1.0, PaneLayout.weightOf(leaf("p1", 0.0)), 0.0)
    }
}
