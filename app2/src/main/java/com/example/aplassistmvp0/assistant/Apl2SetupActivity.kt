package com.example.aplassistmvp0.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.aplassistmvp0.MainActivity

/** The service settings entry reuses the same setup UI as the launcher. */
class AssistantSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SETUP, true))
        finish()
    }
}
