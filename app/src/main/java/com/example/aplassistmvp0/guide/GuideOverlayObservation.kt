package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy
import com.example.aplassistmvp0.accessibility.NodeBounds
import com.example.aplassistmvp0.state.ScreenSnapshot

/** Local presentation evidence only. Coordinates are resolved from the current native tree. */
data class GuideOverlayObservation internal constructor(
    internal val packageName:String,internal val windowId:Int,internal val sourceEpoch:Long,
    internal val sequence:Long,internal val capturedAt:Long,internal val anchors:List<GuideOverlayAnchor>,
)

internal data class GuideOverlayAnchor(val path:String,val meaning:List<Any?>)

internal object GuideOverlayEvidence {
    private fun meaning(node:AccessibilityNodeModel):List<Any?>? {
        if(!node.visibleToUser || node.bounds.isEmpty || AccessibilityNodePrivacy.isSensitivePreview(node) ||
            AccessibilityNodePrivacy.isEditable(node) || AccessibilityNodePrivacy.hasHiddenIdentifier(node))return null
        val label=GuidePolicy.label(node).takeIf{it.isNotBlank()} ?:return null
        return listOf(node.className,node.viewIdResourceName,label,node.stateDescription,node.checked,node.selected,node.enabled,node.range)
    }
    fun capture(frame:ScreenSnapshot,ids:List<Int>):GuideOverlayObservation? {
        if(frame.truncated || frame.viewport==null)return null
        val candidates=ids.distinct().take(180).mapNotNull{id->
            frame.allNodes.singleOrNull{it.id==id}?.let{n->meaning(n)?.let{GuideOverlayAnchor(n.path,it)}}
        }
        // A broad context container must not outweigh its specifically referenced descendants.
        val leaves=candidates.filter{parent->candidates.none{it.path.startsWith(parent.path+"/")}}
        if(leaves.isEmpty())return null
        return GuideOverlayObservation(frame.packageName,frame.windowId,frame.sourceEpoch,frame.snapshotId,frame.capturedAtMillis,leaves)
    }
    fun regions(state:GuideUiState,frame:ScreenSnapshot?):List<NodeBounds> {
        val observation=state.overlayObservation ?:return emptyList()
        if(state.busy || state.phase!="guiding" || state.targetPackageName!=observation.packageName || frame==null ||
            frame.packageName!=observation.packageName || frame.windowId!=observation.windowId || frame.sourceEpoch!=observation.sourceEpoch ||
            frame.snapshotId<observation.sequence || frame.capturedAtMillis<observation.capturedAt || frame.truncated || frame.viewport==null)return emptyList()
        return observation.anchors.mapNotNull{a->
            frame.allNodes.filter{it.path==a.path && meaning(it)==a.meaning}.singleOrNull()?.bounds
        }.distinct()
    }
}
