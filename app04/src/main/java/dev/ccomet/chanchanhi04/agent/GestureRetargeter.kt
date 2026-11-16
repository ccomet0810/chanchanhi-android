package dev.ccomet.chanchanhi04.agent

import org.json.JSONArray
import org.json.JSONObject

/** Keep the approved hand outline/timing; move contact points independently of hand size. */
object GestureRetargeter {
    private val kept=setOf("Hand","Pinch hand","Touch ripple","Contact pressure","Hold progress")
    private fun first(p:JSONObject):JSONArray=if(p.optInt("a")==1)p.getJSONArray("k").getJSONObject(0).getJSONArray("s")else p.getJSONArray("k")
    private fun values(p:JSONObject,change:(JSONArray)->Unit){
        if(p.optInt("a")==0)change(p.getJSONArray("k"))
        else p.getJSONArray("k").let{a->for(i in 0 until a.length()){val k=a.getJSONObject(i);k.optJSONArray("s")?.let(change);k.optJSONArray("e")?.let(change)}}
    }
    fun build(source:String,cue:GestureCue,width:Int,height:Int,density:Float):String {
        val d=JSONObject(source);val layers=d.getJSONArray("layers");val pinch=cue.asset.startsWith("zoom_")
        val pressure=(0 until layers.length()).map(layers::getJSONObject).first{it.getString("nm")=="Contact pressure"}.getJSONObject("ks").getJSONObject("p")
        val start=first(pressure);val sx=if(pinch)252.06 else start.getDouble(0);val sy=if(pinch)145.0 else start.getDouble(1)
        // Swipes reset at the end; the farthest position identifies the shown contact travel.
        var ex=sx;var ey=sy;var far=0.0
        values(pressure){v->val dx=v.getDouble(0)-sx;val dy=v.getDouble(1)-sy;val n=dx*dx+dy*dy;if(n>far){far=n;ex=v.getDouble(0);ey=v.getDouble(1)}}
        val vx=ex-sx;val vy=ey-sy
        val size=(density*.62f).toDouble()
        // Point down from above at the bottom edge, rather than cutting off the whole hand.
        val flip=if(cue.start.y+145*density>height&&cue.start.y>145*density)-1 else 1
        val scale=size*flip
        val output=JSONArray()
        for(i in 0 until layers.length()){
            val layer=layers.getJSONObject(i);val name=layer.getString("nm");if(name !in kept)continue
            val ks=layer.getJSONObject("ks");val p=ks.getJSONObject("p");val initial=first(p)
            val ox=if(name=="Hand")initial.getDouble(0)-sx else 0.0
            val oy=if(name=="Hand")initial.getDouble(1)-sy else 0.0
            values(p){v->
                val x=v.getDouble(0);val y=v.getDouble(1)
                val fraction=if(pinch||far<.001)0.0 else (((x-ox-sx)*vx+(y-oy-sy)*vy)/far).coerceIn(0.0,1.0)
                v.put(0,cue.start.x+(cue.end.x-cue.start.x)*fraction+(x-sx-vx*fraction)*scale)
                v.put(1,cue.start.y+(cue.end.y-cue.start.y)*fraction+(y-sy-vy*fraction)*scale)
            }
            values(ks.getJSONObject("s")){v->v.put(0,v.getDouble(0)*scale);v.put(1,v.getDouble(1)*scale)}
            if(name!="Hand"&&name!="Pinch hand")recolor(layer)
            output.put(layer)
        }
        require(output.length()>0)
        d.put("layers",output).put("w",width).put("h",height)
        return d.toString()
    }
    private fun recolor(value:Any){
        when(value){
            is JSONObject->{
                if(value.optString("ty") in setOf("fl","st"))value.optJSONObject("c")?.takeIf{it.optInt("a")==0}?.put("k",JSONArray(listOf(20/255.0,83/255.0,45/255.0,1)))
                value.keys().forEach{k->recolor(value.get(k))}
            }
            is JSONArray->for(i in 0 until value.length())recolor(value.get(i))
        }
    }
}
