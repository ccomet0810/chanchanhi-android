package com.example.aplassistmvp0.accessibility

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Opt-in, process-local change detector. Neither content nor its keyed digest leaves this object. */
internal object LocalInputRevisions {
    private data class Key(val pkg:String,val window:Int,val path:String,val role:String,val resource:String)
    private data class Entry(var digest:ByteArray,var revision:Long,var bounds:NodeBounds)
    private val entries=linkedMapOf<Key,Entry>()
    private var secret=newSecret()
    private var serial=0L
    @Volatile var changeVersion=0L;private set
    @Volatile private var enabled=false
    private var targetPackage:String?=null
    val active:Boolean get()=enabled
    private var listener:((String,Int)->Unit)?=null
    private fun newSecret()=ByteArray(32).also{SecureRandom().nextBytes(it)}
    @Synchronized fun activate(onMutation:(String,Int)->Unit){enabled=true;targetPackage=null;listener=onMutation}
    @Synchronized fun bind(onMutation:(String,Int)->Unit){listener=onMutation}
    @Synchronized fun enable(packageName:String?=null){enabled=true;targetPackage=packageName}
    @Synchronized fun deactivate(){enabled=false;targetPackage=null;clear()}
    @Synchronized fun clear(){entries.values.forEach{it.digest.fill(0)};entries.clear();secret.fill(0);secret=newSecret();changeVersion++}
    @Synchronized fun capture(pkg:String?,window:Int,path:String,role:String?,resource:String?,bounds:NodeBounds,value:CharSequence?):Long? {
        if(!enabled || pkg.isNullOrBlank() || targetPackage!=null && pkg!=targetPackage || window<0 || value==null || value.length>32_768)return null
        val key=Key(pkg,window,path,role.orEmpty(),resource.orEmpty())
        val mac=Mac.getInstance("HmacSHA256").apply{init(SecretKeySpec(secret,"HmacSHA256"))}
        val bytes=value.toString().toByteArray(Charsets.UTF_8)
        val digest=try{mac.doFinal(bytes)}finally{bytes.fill(0)}
        val old=entries[key]
        if(old==null){entries[key]=Entry(digest,++serial,bounds)}
        else{if(!old.digest.contentEquals(digest)){old.digest.fill(0);old.digest=digest;old.revision=++serial}
            else digest.fill(0)
            old.bounds=bounds}
        while(entries.size>128){val first=entries.keys.first();entries.remove(first)?.digest?.fill(0)}
        return entries[key]?.revision
    }
    fun changed(pkg:String,window:Int,role:String,resource:String,bounds:NodeBounds){
        val callback=synchronized(this){
            if(!enabled || targetPackage!=null && pkg!=targetPackage)return
            changeVersion++
            val sameWindow=entries.filterKeys{it.pkg==pkg && it.window==window}
            val identities=sameWindow.filterKeys{it.role==role && (resource.isBlank() || it.resource==resource)}
            val positioned=identities.filterValues{it.bounds==bounds}
            val affected=when{positioned.size==1->positioned;identities.size==1 && resource.isNotBlank()->identities;else->sameWindow}
            affected.values.forEach{it.revision=++serial}
            listener
        }
        callback?.invoke(pkg,window)
    }
}
