package dev.ccomet.chanchanhi04.admin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AdminAttempts(val failures:Int=0,val blockedUntil:Long=0)
interface AdminAttemptStore {fun read():AdminAttempts;fun write(value:AdminAttempts)}
data class AdminStatus(val unlocked:Boolean=false,val attempts:AdminAttempts=AdminAttempts())

/** Local UI gate. Authentication is memory-only; backgrounding invalidates even an in-flight PIN check. */
class AdminAccess(private val store:AdminAttemptStore,private val now:()->Long=System::currentTimeMillis,
                  private val verifier:(CharArray)->Boolean=AdminPinVerifier::matches) {
    private val mutex=Any()
    private var generation=0L
    private val mutable=MutableStateFlow(AdminStatus(attempts=store.read()))
    val state=mutable.asStateFlow()
    val unlocked get()=state.value.unlocked
    fun remainingSeconds():Long=((state.value.attempts.blockedUntil-now()).coerceAtLeast(0)+999)/1000
    fun lock()=synchronized(mutex){generation++;mutable.value=mutable.value.copy(unlocked=false)}
    fun verify(pin:CharArray):Boolean {
        val token=synchronized(mutex) {
            if(remainingSeconds()>0){pin.fill('\u0000');return false}
            generation
        }
        val accepted=try{verifier(pin)}finally{pin.fill('\u0000')}
        return synchronized(mutex) {
            if(token!=generation || remainingSeconds()>0)return@synchronized false
            if(accepted) {
                store.write(AdminAttempts());mutable.value=AdminStatus(true)
                true
            } else {
                val count=state.value.attempts.failures+1
                val attempts=if(count>=5)AdminAttempts(blockedUntil=now()+30000)else AdminAttempts(count)
                store.write(attempts);mutable.value=AdminStatus(attempts=attempts)
                false
            }
        }
    }
}
