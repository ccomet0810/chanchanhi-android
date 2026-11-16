package dev.ccomet.chanchanhi04.admin

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AdminAccessTest {
    private class Memory:AdminAttemptStore {
        var saved=AdminAttempts()
        override fun read()=saved
        override fun write(value:AdminAttempts){saved=value}
    }
    @Test fun accessStartsLockedAndDoesNotPersistAuthentication() {
        val store=Memory();val access=AdminAccess(store,verifier={true})
        assertFalse(access.unlocked)
        val pin="sample".toCharArray();assertTrue(access.verify(pin))
        assertTrue(pin.all{it=='\u0000'});assertTrue(access.unlocked)
        assertFalse(AdminAccess(store,verifier={true}).unlocked)
        access.lock();assertFalse(access.unlocked)
    }
    @Test fun repeatedFailuresRemainBlockedAcrossRecreationThenExpire() {
        val store=Memory();var clock=1000L;var checked=0
        val access=AdminAccess(store,{clock},{checked++;false})
        repeat(5){assertFalse(access.verify(charArrayOf('x')))}
        assertEquals(30L,access.remainingSeconds())
        val reopened=AdminAccess(store,{clock},{checked++;true})
        val blocked=charArrayOf('x');assertFalse(reopened.verify(blocked));assertEquals(5,checked)
        assertTrue(blocked.all{it=='\u0000'})
        clock+=30000;assertTrue(reopened.verify(charArrayOf('x')))
        assertEquals(AdminAttempts(),store.saved)
    }
    @Test fun lockInvalidatesPinCheckAlreadyRunningInBackground() {
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        val store=Memory();val access=AdminAccess(store,verifier={entered.countDown();finish.await(2,TimeUnit.SECONDS);true})
        var result=true;val pin=charArrayOf('x')
        val worker=Thread{result=access.verify(pin)}.apply{start()}
        assertTrue(entered.await(2,TimeUnit.SECONDS));access.lock();finish.countDown();worker.join(3000)
        assertFalse(worker.isAlive);assertFalse(result);assertFalse(access.unlocked)
        assertTrue(pin.all{it=='\u0000'});assertEquals(AdminAttempts(),store.saved)
    }
    @Test fun successfulAuthenticationClearsEarlierFailures() {
        val store=Memory();var accepted=false;val access=AdminAccess(store,verifier={accepted})
        repeat(4){access.verify(charArrayOf('x'))};assertEquals(4,store.saved.failures)
        accepted=true;assertTrue(access.verify(charArrayOf('x')))
        assertEquals(AdminAttempts(),store.saved)
    }
    @Test fun verifierFailureStillErasesInput() {
        val access=AdminAccess(Memory(),verifier={error("unavailable")});val pin=charArrayOf('x')
        runCatching{access.verify(pin)}
        assertFalse(access.unlocked);assertTrue(pin.all{it=='\u0000'})
    }
    @Test fun pinVerifierRejectsMalformedInput() {
        assertFalse(AdminPinVerifier.matches(charArrayOf()))
        assertFalse(AdminPinVerifier.matches("abcdef".toCharArray()))
        assertFalse(AdminPinVerifier.matches("00000".toCharArray()))
    }
}
