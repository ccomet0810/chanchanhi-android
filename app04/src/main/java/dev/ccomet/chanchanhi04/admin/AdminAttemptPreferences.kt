package dev.ccomet.chanchanhi04.admin

import android.content.Context

class AdminAttemptPreferences(context:Context):AdminAttemptStore {
    private val preferences=context.applicationContext.getSharedPreferences("admin_attempts",Context.MODE_PRIVATE)
    override fun read()=AdminAttempts(preferences.getInt("failures",0).coerceIn(0,4),preferences.getLong("blocked_until",0))
    override fun write(value:AdminAttempts){preferences.edit().putInt("failures",value.failures).putLong("blocked_until",value.blockedUntil).apply()}
}
