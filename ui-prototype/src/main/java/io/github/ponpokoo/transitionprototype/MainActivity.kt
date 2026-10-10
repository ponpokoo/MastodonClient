package io.github.ponpokoo.transitionprototype

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().safeDrawingPadding()
                            .verticalScroll(rememberScrollState()).padding(20.dp)
                            .testTag("prototype_menu"),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("UIの試作", style = MaterialTheme.typography.headlineSmall)
                        Text("デザインや画面遷移の操作感を試せます。")
                        Text("画像遷移", style = MaterialTheme.typography.titleMedium)
                        Text("単一画像を開閉し、元の位置に戻る動きを確認します。")
                        Button(
                            onClick = {
                                startActivity(Intent(this@MainActivity, ImageTransitionActivity::class.java))
                            },
                            modifier = Modifier.fillMaxWidth().testTag("single_image_sample"),
                        ) { Text("単一画像の遷移") }
                        Text("複数画像の切り替え・ズームと、閉じる動きを確認します。")
                        Button(
                            onClick = {
                                startActivity(Intent(this@MainActivity, ReferenceGalleryActivity::class.java))
                            },
                            modifier = Modifier.fillMaxWidth().testTag("reference_sample"),
                        ) { Text("複数画像・ズーム") }
                    }
                }
            }
        }
    }
}
