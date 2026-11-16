package dev.ccomet.chanchanhi04.setup

/** Manual illustration state and disposable entry tickets; no timer can grant permission. */
class VisualSetupSession(private val size:Int,index:Int=0,settingsOpened:Boolean=false,generation:Int=0) {
    init {require(size>0)}
    var index=index.coerceIn(0,size-1);private set
    var settingsOpened=settingsOpened;private set
    var generation=generation;private set
    var completed=false;private set
    private var entry:Int?=null
    fun requestEntry():Int? {
        if(entry!=null||completed)return null
        generation++;entry=generation;return generation
    }
    fun entryIsCurrent(ticket:Int)=entry==ticket&&!completed
    fun consumeEntry(ticket:Int):Boolean {
        if(!entryIsCurrent(ticket))return false
        entry=null;return true
    }
    fun cancelEntry(){generation++;entry=null}
    fun beginSettings(){settingsOpened=true}
    fun move(delta:Int):Int {index=ScreenSetupTour.move(index,delta,size);return index}
    fun observe(permission:Boolean,connected:Boolean):Boolean {
        if(settingsOpened&&permission&&connected){completed=true;entry=null}
        return completed
    }
    fun restart(){cancelEntry();index=0;settingsOpened=false;completed=false}
}
