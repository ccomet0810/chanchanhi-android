package dev.ccomet.chanchanhi04.ui

/** Both rounded rectangles share one center: inner radius is outer radius minus bezel. */
internal data class SimulationPhoneGeometry(val outerRadius:Float,val bezel:Float){
    val innerRadius get()=outerRadius-bezel
    companion object{fun forWidth(width:Float):SimulationPhoneGeometry{require(width>=0);return SimulationPhoneGeometry(width*.065f,width*.015f)}}
}
