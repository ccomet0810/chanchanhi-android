package dev.ccomet.chanchanhi04.agent

import com.example.aplassistmvp0.accessibility.NodeBounds

data class ContactPoint(val x:Float,val y:Float)
data class GestureCue(val asset:String,val start:ContactPoint,val end:ContactPoint,val outline:Boolean=true)

/** Action semantics only. Never guess a gesture from an app name or instruction sentence. */
object GestureMapping {
    val assets=listOf("tap","double_tap","long_press","swipe_up","swipe_down","swipe_left","swipe_right","drag_drop","hold_drag","zoom_in","zoom_out")
    private fun point(path:List<Int>,index:Int,width:Int,height:Int)=ContactPoint(path[index]*(width-1)/1000f,path[index+1]*(height-1)/1000f)
    private fun inside(p:ContactPoint,b:NodeBounds)=p.x>=b.left&&p.x<b.right&&p.y>=b.top&&p.y<b.bottom
    fun pathValid(action:Action,path:List<Int>?,target:NodeBounds?,width:Int,height:Int):Boolean {
        val needsPath=action in setOf(Action.DRAG,Action.HOLD_DRAG,Action.ADJUST)
        if(path==null)return !needsPath || action==Action.ADJUST // Old ADJUST remains text-only.
        if(!needsPath||target==null||width<=1||height<=1||path.size!=4||path.any{it !in 0..1000}||path.take(2)==path.drop(2))return false
        return inside(point(path,0,width,height),target)&&(action!=Action.ADJUST||inside(point(path,2,width,height),target))
    }
    fun resolve(action:Action?,target:NodeBounds?,width:Int,height:Int,path:List<Int>?=null):GestureCue? {
        if(action==null||width<=1||height<=1)return null
        if(action in setOf(Action.NOTIFICATIONS,Action.QUICK_SETTINGS)){
            val x=width*.5f;return GestureCue("swipe_down",ContactPoint(x,1f),ContactPoint(x,height*.28f),false)
        }
        val b=target?.takeUnless{it.isEmpty}?:return null
        if(b.left<0||b.top<0||b.right>width||b.bottom>height||!pathValid(action,path,b,width,height))return null
        val center=ContactPoint((b.left+b.right)/2f,(b.top+b.bottom)/2f)
        val left=ContactPoint(b.left+(b.right-b.left)*.2f,center.y);val right=ContactPoint(b.left+(b.right-b.left)*.8f,center.y)
        val top=ContactPoint(center.x,b.top+(b.bottom-b.top)*.2f);val bottom=ContactPoint(center.x,b.top+(b.bottom-b.top)*.8f)
        return when(action){
            Action.TAP,Action.INPUT->GestureCue("tap",center,center)
            Action.DOUBLE_TAP->GestureCue("double_tap",center,center)
            Action.LONG_PRESS->GestureCue("long_press",center,center)
            Action.SWIPE_UP->GestureCue("swipe_up",bottom,top)
            Action.SWIPE_DOWN->GestureCue("swipe_down",top,bottom)
            Action.SWIPE_LEFT->GestureCue("swipe_left",right,left)
            Action.SWIPE_RIGHT->GestureCue("swipe_right",left,right)
            Action.DRAG,Action.HOLD_DRAG,Action.ADJUST->path?.let{GestureCue(if(action==Action.HOLD_DRAG)"hold_drag"else"drag_drop",point(it,0,width,height),point(it,2,width,height))}
            Action.ZOOM_IN->GestureCue("zoom_in",center,center)
            Action.ZOOM_OUT->GestureCue("zoom_out",center,center)
            else->null // Back/home may be buttons or edge gestures. Do not invent a device navigation mode.
        }
    }
}
