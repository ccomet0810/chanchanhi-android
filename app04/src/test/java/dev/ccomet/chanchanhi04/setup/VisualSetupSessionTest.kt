package dev.ccomet.chanchanhi04.setup

import org.junit.Assert.*
import org.junit.Test

class VisualSetupSessionTest {
    @Test fun visitingEveryExampleNeverGrantsPermissionOrClosesTheLastExample() {
        val session=VisualSetupSession(4)
        session.beginSettings()
        repeat(100){session.move(1);assertFalse(session.observe(false,false))}
        assertEquals(3,session.index);assertFalse(session.completed)
        session.move(0);assertEquals(3,session.index)
    }
    @Test fun actualGrantAndLiveConnectionAreBothRequiredWithoutAnActivityReturn() {
        val session=VisualSetupSession(4)
        assertFalse(session.observe(true,true))
        session.beginSettings()
        assertFalse(session.observe(true,false))
        assertFalse(session.observe(false,true))
        assertTrue(session.observe(true,true))
        assertNull(session.requestEntry())
    }
    @Test fun aLateFrameCannotReopenSettingsAfterCancelOrNewIntent() {
        val session=VisualSetupSession(4)
        val cancelled=session.requestEntry()!!
        assertNull(session.requestEntry())
        session.cancelEntry();assertFalse(session.consumeEntry(cancelled))
        val old=session.requestEntry()!!
        session.restart();val current=session.requestEntry()!!
        assertFalse(session.consumeEntry(old));assertTrue(session.consumeEntry(current))
        assertFalse(session.consumeEntry(current))
    }
    @Test fun restorationKeepsTheExampleButNeverRestoresAPendingLaunch() {
        val old=VisualSetupSession(4);old.move(1);old.beginSettings();val ticket=old.requestEntry()!!
        val restored=VisualSetupSession(4,old.index,old.settingsOpened,old.generation)
        assertEquals(1,restored.index);assertFalse(restored.consumeEntry(ticket))
        assertFalse(restored.observe(true,false));assertTrue(restored.observe(true,true))
    }
}
