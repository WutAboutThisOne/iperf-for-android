package local.net.iperf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import local.net.iperf.ui.AppViewModel
import local.net.iperf.ui.adaptive.LocalAdaptive
import local.net.iperf.ui.adaptive.rememberAdaptive
import local.net.iperf.ui.IperfApp
import local.net.iperf.ui.theme.IperfTheme

/**
 * Single activity hosting the Compose UI ([local.net.iperf.ui.IperfApp]). All state lives in
 * [local.net.iperf.ui.AppViewModel] and the process-wide [AppContainer], so rotation and fold/unfold
 * changes never interrupt a running test.
 */
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            IperfTheme {
                CompositionLocalProvider(LocalAdaptive provides rememberAdaptive(this)) { IperfApp(vm) }
            }
        }
    }
}
