package top.maler.motogps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import android.app.Application
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import top.maler.motogps.ble.BleRepository

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels { MainViewModel.Factory(application) }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) model.scan() else model.setPermissionMessage("蓝牙权限未全部授予。请在系统设置中允许附近设备访问后重试。")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by model.state.collectAsState()
                GlimpseHome(
                    state = state,
                    onScan = {
                        val needed = model.permissionsNeeded()
                        val missing = needed.filter { androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                        if (missing.isEmpty()) model.scan() else permissionLauncher.launch(missing.toTypedArray())
                    },
                    onConnect = model::connect,
                    onDisconnect = model::disconnect,
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) model.disconnect()
        super.onDestroy()
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BleRepository(application.applicationContext)
    val state: StateFlow<BleRepository.State> = repository.state
    fun scan() = repository.startScan()
    fun connect(address: String) = repository.connect(address)
    fun disconnect() = repository.disconnect()
    fun permissionsNeeded(): Array<String> = repository.permissionsNeeded()
    fun setPermissionMessage(message: String) = repository.reportMessage(message)

    override fun onCleared() { repository.close(); super.onCleared() }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) return MainViewModel(application) as T
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

@Composable
private fun GlimpseHome(
    state: BleRepository.State,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var gateway by remember { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Glimpse · MOTO GPS", style = MaterialTheme.typography.headlineSmall)
            Text("Android 社区移植 · 0.1.0-alpha01", style = MaterialTheme.typography.bodyMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("圆屏连接", style = MaterialTheme.typography.titleMedium)
                    Text(state.status, style = MaterialTheme.typography.bodyMedium)
                    Text("协议：${if (state.protocolReady) "v1 Ready" else "尚未 Ready"}    MTU：${state.mtu}    最大帧：${state.maxFrameSize} B")
                    Text(state.lastFrame, style = MaterialTheme.typography.bodySmall)
                    if (state.error != null) Text("提示：${state.error}", color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onScan, enabled = !state.scanning && !state.connected) { Text(if (state.scanning) "扫描中…" else "扫描圆屏") }
                        OutlinedButton(onClick = onDisconnect, enabled = state.connected) { Text("断开") }
                    }
                }
            }
            Text("扫描到的设备（请确认名称与地址后再连接）", style = MaterialTheme.typography.titleMedium)
            if (state.devices.isEmpty()) Text("暂无设备。确保圆屏已开机且在附近。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.devices, key = { it.address }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleSmall)
                            Text(item.address, style = MaterialTheme.typography.bodySmall)
                            Text("RSSI ${item.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { onConnect(item.address) }, enabled = !state.connected) { Text("连接此设备") }
                        }
                    }
                }
            }
            Divider()
            OutlinedTextField(
                value = gateway,
                onValueChange = { gateway = it },
                label = { Text("导航网关 HTTPS 根地址（阶段 2 接入）") },
                placeholder = { Text("https://你的域名/moto-gps/api/") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text("当前阶段只验证 BLE 安全连接、v1 握手与心跳；还没有真实定位、路线搜索或地图下载。网关地址暂未保存或调用。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(2.dp))
        }
    }
}
