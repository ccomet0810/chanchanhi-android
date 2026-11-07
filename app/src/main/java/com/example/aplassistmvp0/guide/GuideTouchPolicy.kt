package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.NodeBounds
import com.example.aplassistmvp0.state.ScreenSnapshot

/** A visible accessibility row can extend underneath a toolbar or floating control.
 * Keep node identity unchanged; only the displayed touch rectangle is narrowed.
 * Ancestors and children are excluded from the clickable-control overlap check.
 */
internal object GuideTouchPolicy {
    fun exposedBounds(screen:ScreenSnapshot,node:AccessibilityNodeModel,viewport:NodeBounds,minimumEdge:Int=32):NodeBounds? {
        fun intersection(a:NodeBounds,b:NodeBounds)=NodeBounds(maxOf(a.left,b.left),maxOf(a.top,b.top),
            minOf(a.right,b.right),minOf(a.bottom,b.bottom)).takeUnless { it.isEmpty }
        fun area(a:NodeBounds)=(a.right-a.left).toLong()*(a.bottom-a.top)
        val clipped=intersection(node.bounds,viewport) ?: return null
        val toolbar = screen.platformActionBarBounds?.takeIf { bar ->
            intersection(clipped, bar) != null && screen.allNodes.any { list ->
                list.scrollable && list.visibleToUser && list.bounds.bottom > bar.bottom && intersection(list.bounds, bar) != null &&
                    (node.path == list.path || node.path.startsWith(list.path + "/"))
            }
        }
        val scrollRegion = node.scrollable && !node.clickable
        if(scrollRegion && toolbar == null) return clipped
        var regions=listOf(clipped)
        val controls = if (scrollRegion) emptyList() else screen.allNodes.filter { other ->
            other.id>node.id && other.visibleToUser && other.enabled && other.clickable &&
            !other.bounds.isEmpty && !other.path.startsWith(node.path+"/") && !node.path.startsWith(other.path+"/") &&
            intersection(clipped,other.bounds)!=null }.take(32).map { it.bounds }
        for(blocker in listOfNotNull(toolbar) + controls) {
            regions=regions.flatMap { r ->
                val cut=intersection(r,blocker) ?: return@flatMap listOf(r)
                listOf(NodeBounds(r.left,r.top,r.right,cut.top),NodeBounds(r.left,cut.bottom,r.right,r.bottom),
                    NodeBounds(r.left,cut.top,cut.left,cut.bottom),NodeBounds(cut.right,cut.top,r.right,cut.bottom))
                    .filter { it.right-it.left>=minimumEdge && it.bottom-it.top>=minimumEdge }
            }.sortedByDescending(::area).take(32)
        }
        return regions.filter { it.right-it.left>=minimumEdge && it.bottom-it.top>=minimumEdge }.maxByOrNull(::area)
    }
}
