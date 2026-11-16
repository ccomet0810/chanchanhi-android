package dev.ccomet.chanchanhi04.setup

data class VisualSetupPlacement(val top:Int,val height:Int,val avoidsTarget:Boolean)

/** A full-width panel may scroll within a free vertical band instead of covering its target. */
object VisualSetupAvoidance {
    fun place(safeTop:Int,safeBottom:Int,height:Int,currentTop:Int,targetTop:Int?=null,targetBottom:Int?=null,gap:Int=0):VisualSetupPlacement {
        require(safeBottom>safeTop&&height>0&&gap>=0)
        val desiredHeight=height.coerceAtMost(safeBottom-safeTop)
        val current=currentTop.coerceIn(safeTop,safeBottom-desiredHeight)
        if(targetTop==null||targetBottom==null||targetBottom<=safeTop||targetTop>=safeBottom)
            return VisualSetupPlacement(current,desiredHeight,true)
        val blockedTop=(targetTop-gap).coerceIn(safeTop,safeBottom)
        val blockedBottom=(targetBottom+gap).coerceIn(safeTop,safeBottom)
        if(current+desiredHeight<=blockedTop||current>=blockedBottom)return VisualSetupPlacement(current,desiredHeight,true)
        val above=blockedTop-safeTop
        val below=safeBottom-blockedBottom
        if(above==0&&below==0)return VisualSetupPlacement(current,desiredHeight,false)
        val useAbove=when {
            above>=desiredHeight&&below>=desiredHeight->kotlin.math.abs(current-(blockedTop-desiredHeight))<=kotlin.math.abs(current-blockedBottom)
            above>=desiredHeight->true
            below>=desiredHeight->false
            else->above>=below
        }
        val fitted=desiredHeight.coerceAtMost(if(useAbove)above else below)
        val top=if(useAbove)(current.coerceIn(safeTop,blockedTop-fitted))else current.coerceIn(blockedBottom,safeBottom-fitted)
        return VisualSetupPlacement(top,fitted,true)
    }
}
