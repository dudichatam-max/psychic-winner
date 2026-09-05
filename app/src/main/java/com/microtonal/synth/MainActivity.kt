package com.microtonal.synth
 
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {
    private lateinit var synthEngine: SynthEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        synthEngine = SynthEngine(this)
        synthEngine.start()

        setContent {
            MaterialTheme {
                SynthAppUI(synthEngine)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // After lock-screen / GPU surface loss the audio thread keeps running
        // while Compose may stop drawing. Invalidate the window so the tree
        // reattaches without recreating SynthEngine.
        window.decorView.post { window.decorView.invalidate() }
    }

    override fun onDestroy() {
        super.onDestroy()
        synthEngine.stop()
    }
}
