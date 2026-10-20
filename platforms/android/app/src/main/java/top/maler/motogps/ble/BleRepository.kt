package top.maler.motogps.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID

class BleRepository(private val appContext: Context) {
    data class DeviceItem(val device: BluetoothDevice, val name: String, val address: String, val rssi: Int)
    data class State(
        val scanning: Boolean = false,
        val devices: List<DeviceItem> = emptyList(),
        val selectedAddress: String? = null,
        val status: String = "未连接",
        val connected: Boolean = false,
        val protocolReady: Boolean = false,
        val mtu: Int = 23,
        val maxFrameSize: Int = 20,
        val lastFrame: String = "尚未收到设备数据",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var sessionId: Long = 0
    private var sessionStartedElapsed: Long = 0
    private var txSequence = 1
    private var negotiatedFrameSize = 20
    private val phoneCapabilities = 0x00L // Phase 1 implements no business capabilities yet; do not overclaim navigation/map/music.
    private var firstDeviceReady: BleProtocol.ConnectionStatus? = null
    private val reassembler = MessageReassembler()
    private val pendingWrites = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var bondReceiverRegistered = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (_state.value.protocolReady && gatt != null) {
                val elapsed = SystemClock.elapsedRealtime() - sessionStartedElapsed
                sendLogical(BleProtocol.TYPE_HEARTBEAT, 0, BleProtocol.encodeHeartbeat(sessionId, elapsed))
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice)
            if (device?.address != _state.value.selectedAddress) return
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            if (bondState == BluetoothDevice.BOND_BONDED && gatt != null && tx != null) {
                mainHandler.postDelayed({ enableTxNotifications() }, 250L)
            }
        }
    }

    fun bluetoothReady(): Boolean = adapter?.isEnabled == true

    fun permissionsNeeded(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasRequiredPermissions()) { setError("缺少蓝牙权限，请先授权附近设备权限"); return }
        val a = adapter
        if (a == null) { setError("此手机没有蓝牙适配器"); return }
        if (!a.isEnabled) { setError("请先打开手机蓝牙"); return }
        val s = scanner
        if (s == null) { setError("BLE 扫描器不可用"); return }
        stopScan()
        _state.value = _state.value.copy(scanning = true, devices = emptyList(), error = null, status = "正在按 MOTO GPS 服务 UUID 扫描…")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NAVIGATION_SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { s.startScan(listOf(filter), settings, scanCallback) }
        catch (e: SecurityException) { setError("蓝牙扫描权限被系统拒绝：${e.message}") }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) { }
        _state.value = _state.value.copy(scanning = false)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasRequiredPermissions()) { setError("缺少蓝牙连接权限"); return }
        stopScan()
        val item = _state.value.devices.firstOrNull { it.address == address }
        val device = item?.device ?: try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null }
        if (device == null) { setError("找不到所选设备，请重新扫描"); return }
        closeGatt()
        resetProtocol()
        _state.value = _state.value.copy(selectedAddress = address, connected = false, protocolReady = false,
            status = "正在连接 ${item?.name ?: address}…", error = null, lastFrame = "尚未收到设备数据")
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") appContext.registerReceiver(bondReceiver, filter)
            bondReceiverRegistered = true
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                else device.connectGatt(appContext, false, gattCallback)
        } catch (e: SecurityException) { setError("连接权限被系统拒绝：${e.message}") }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        mainHandler.removeCallbacks(heartbeatRunnable)
        try { gatt?.disconnect() } catch (_: Exception) { }
        closeGatt()
        resetProtocol()
        _state.value = _state.value.copy(connected = false, protocolReady = false, status = "已断开", mtu = 23, maxFrameSize = 20)
    }

    fun close() { disconnect() }
    fun reportMessage(message: String) { _state.value = _state.value.copy(status = message, error = message) }

    @SuppressLint("MissingPermission")
    private fun enableTxNotifications() {
        val currentGatt = gatt ?: return
        val characteristic = tx ?: return
        try {
            if (!currentGatt.setCharacteristicNotification(characteristic, true)) {
                setError("无法启用 TX Notify"); return
            }
            val cccd = characteristic.getDescriptor(CCCD_UUID)
            if (cccd == null) { setError("TX 特征缺少标准 CCCD (0x2902)"); return }
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                currentGatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") currentGatt.writeDescriptor(cccd)
            }
            if (!accepted) setError("提交 TX Notify 订阅失败") else _state.value = _state.value.copy(status = "正在订阅加密 TX Notify…")
        } catch (e: SecurityException) { setError("订阅通知需要蓝牙连接权限：${e.message}") }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = try { device.address } catch (_: SecurityException) { return }
            val name = try { device.name ?: result.scanRecord?.deviceName ?: "未命名 MOTO GPS 设备" } catch (_: SecurityException) { "MOTO GPS 设备" }
            val current = _state.value.devices.toMutableList()
            val existing = current.indexOfFirst { it.address == address }
            val item = DeviceItem(device, name, address, result.rssi)
            if (existing >= 0) current[existing] = item else current.add(item)
            _state.value = _state.value.copy(devices = current.sortedByDescending { it.rssi })
        }
        override fun onScanFailed(errorCode: Int) { _state.value = _state.value.copy(scanning = false, error = "BLE 扫描失败，错误码 $errorCode") }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setError("GATT 连接状态异常：status=$status"); closeGatt(); return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = _state.value.copy(connected = true, status = "已连接，正在发现服务…")
                if (!g.discoverServices()) setError("无法启动 GATT 服务发现")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mainHandler.removeCallbacks(heartbeatRunnable)
                resetProtocol()
                _state.value = _state.value.copy(connected = false, protocolReady = false, status = "连接断开；可重新扫描并连接", mtu = 23, maxFrameSize = 20)
                closeGatt()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { setError("服务发现失败：status=$status"); return }
            val service = g.getService(NAVIGATION_SERVICE_UUID)
            rx = service?.getCharacteristic(PHONE_TO_DEVICE_UUID)
            tx = service?.getCharacteristic(DEVICE_TO_PHONE_UUID)
            if (service == null || rx == null || tx == null) {
                setError("没有找到 MOTO GPS 导航服务/ RX / TX 特征；请核对圆屏固件版本"); return
            }
            if ((rx!!.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0 ||
                (tx!!.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                setError("RX/TX 特征属性与协议不符；拒绝继续连接"); return
            }
            _state.value = _state.value.copy(status = "发现导航服务，正在协商 MTU…")
            try {
                if (!g.requestMtu(247)) enableTxNotifications()
            } catch (e: SecurityException) { setError("请求 MTU 失败：${e.message}") }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            val actualMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            val frameLimit = (actualMtu - 3).coerceIn(20, 512)
            _state.value = _state.value.copy(mtu = actualMtu, maxFrameSize = frameLimit,
                status = "MTU=$actualMtu，正在启用加密 TX Notify…")
            enableTxNotifications()
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = _state.value.copy(status = "TX Notify 已订阅，开始协议握手…")
                beginHandshake()
            } else if (status == 5 || status == 15) { // insufficient authentication / encryption
                _state.value = _state.value.copy(status = "圆屏要求加密配对；等待 Android 系统配对确认…")
                try { gatt.device.createBond() } catch (e: Exception) { setError("无法发起蓝牙配对：${e.message}") }
            } else setError("TX Notify 订阅失败：GATT status=$status；不会关闭固件加密要求来绕过")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value?.clone() ?: return
            handleNotification(characteristic, value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(characteristic, value.clone())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != PHONE_TO_DEVICE_UUID) return
            synchronized(pendingWrites) {
                writeInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    pendingWrites.clear()
                    _state.value = _state.value.copy(protocolReady = false, error = "GATT 写入失败 status=$status；该结果不代表应用层 ACK")
                    setError("GATT 写入失败 status=$status；该结果不代表应用层 ACK")
                    return
                }
                pumpWrites()
            }
        }
    }

    private fun handleNotification(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        if (characteristic.uuid != DEVICE_TO_PHONE_UUID) return
        try {
            val frame = BleProtocol.decodeFrame(bytes)
            val complete = reassembler.accept(frame, SystemClock.elapsedRealtime())
            _state.value = _state.value.copy(lastFrame = "收到 type=0x${frame.type.toString(16)}, seq=${frame.sequence}, ${bytes.size} bytes")
            if (complete != null && complete.type == BleProtocol.TYPE_CONNECTION_STATUS) handleConnectionStatus(BleProtocol.decodeConnectionStatus(complete.payload))
        } catch (e: Exception) {
            Log.w(TAG, "Rejected BLE protocol frame", e)
            _state.value = _state.value.copy(lastFrame = "已拒绝无效协议帧：${e.message}")
        }
    }

    private fun beginHandshake() {
        val random = SecureRandom()
        do { sessionId = random.nextInt().toLong() and 0xFFFF_FFFFL } while (sessionId == 0L)
        sessionStartedElapsed = SystemClock.elapsedRealtime()
        txSequence = 1
        firstDeviceReady = null
        reassembler.reset()
        negotiatedFrameSize = _state.value.maxFrameSize.coerceIn(20, 512)
        val starting = BleProtocol.ConnectionStatus(
            role = 1, state = 0, minVersion = 1, maxVersion = 1,
            capabilities = phoneCapabilities, sessionId = sessionId,
            maxFrameSize = negotiatedFrameSize, heartbeatIntervalMs = 1_000,
        )
        sendLogical(BleProtocol.TYPE_CONNECTION_STATUS, 0, BleProtocol.encodeConnectionStatus(starting))
        _state.value = _state.value.copy(status = "已发送 Phone/Starting，等待设备 Ready…")
    }

    private fun handleConnectionStatus(status: BleProtocol.ConnectionStatus) {
        if (status.role != 2) return
        if (status.sessionId != sessionId) { setError("设备返回的 session_id 不匹配，拒绝旧会话数据"); return }
        if (status.state == 3) { setError("设备拒绝协议版本或正在关闭连接"); return }
        if (status.state != 1) { _state.value = _state.value.copy(status = "设备握手状态=${status.state}"); return }
        if (status.minVersion > BleProtocol.VERSION || status.maxVersion < BleProtocol.VERSION) {
            setError("圆屏不支持 BLE 协议 v1；不发送业务数据"); return
        }
        if (status.maxFrameSize !in 13..negotiatedFrameSize || status.heartbeatIntervalMs < 250) {
            setError("设备协商参数不合法（frame=${status.maxFrameSize}, heartbeat=${status.heartbeatIntervalMs}）"); return
        }
        val previous = firstDeviceReady
        if (previous == null) {
            firstDeviceReady = status
            negotiatedFrameSize = minOf(_state.value.maxFrameSize, status.maxFrameSize)
            val phoneReady = status.copy(role = 1, state = 1, capabilities = status.capabilities and phoneCapabilities,
                maxFrameSize = negotiatedFrameSize)
            sendLogical(BleProtocol.TYPE_CONNECTION_STATUS, 0, BleProtocol.encodeConnectionStatus(phoneReady))
            _state.value = _state.value.copy(status = "收到首次 Device/Ready，已回送 Phone/Ready；等待最终确认…", maxFrameSize = negotiatedFrameSize)
        } else {
            if (!previous.sameNegotiatedValues(status)) {
                setError("最终 Device/Ready 与首次协商结果不一致；协议未就绪"); return
            }
            _state.value = _state.value.copy(protocolReady = true, status = "协议握手完成；BLE 已就绪（导航数据阶段尚未接入）")
            mainHandler.removeCallbacks(heartbeatRunnable)
            mainHandler.postDelayed(heartbeatRunnable, status.heartbeatIntervalMs.toLong())
        }
    }

    private fun sendLogical(type: Int, flags: Int, payload: ByteArray) {
        if (rx == null || gatt == null) return
        val sequence = txSequence
        txSequence = if (txSequence == 65535) 1 else txSequence + 1
        val frameSize = negotiatedFrameSize.coerceIn(20, 512)
        val frames = try { BleProtocol.encodeMessage(type, flags, sequence, payload, frameSize) }
        catch (e: Exception) { setError("无法编码协议消息：${e.message}"); return }
        synchronized(pendingWrites) {
            // Append every fragment while holding one lock: fragments from two messages cannot interleave.
            if (pendingWrites.size + frames.size > MAX_PENDING_FRAMES) {
                pendingWrites.clear()
                writeInFlight = false
                _state.value = _state.value.copy(protocolReady = false, error = "发送队列背压超过上限；协议会话已停止，需重新连接")
                return
            }
            pendingWrites.addAll(frames)
            pumpWrites()
        }
    }

    @SuppressLint("MissingPermission")
    private fun pumpWrites() {
        if (writeInFlight || pendingWrites.isEmpty()) return
        val g = gatt ?: run { pendingWrites.clear(); return }
        val characteristic = rx ?: run { pendingWrites.clear(); return }
        val bytes = pendingWrites.first()
        val accepted = try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION") characteristic.value = bytes
                @Suppress("DEPRECATION") g.writeCharacteristic(characteristic)
            }
        } catch (e: Exception) { setError("GATT 写入提交失败：${e.message}"); false }
        if (accepted) { pendingWrites.removeFirst(); writeInFlight = true }
        else {
            pendingWrites.clear()
            setError("GATT 写入背压/提交失败；已清空待发分片，需重新握手")
        }
    }

    private fun resetProtocol() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        synchronized(pendingWrites) { pendingWrites.clear(); writeInFlight = false }
        sessionId = 0; txSequence = 1; firstDeviceReady = null; reassembler.reset()
        negotiatedFrameSize = 20
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null; rx = null; tx = null
        if (bondReceiverRegistered) {
            try { appContext.unregisterReceiver(bondReceiver) } catch (_: Exception) { }
            bondReceiverRegistered = false
        }
    }

    private fun hasRequiredPermissions(): Boolean = permissionsNeeded().all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun setError(message: String) { _state.value = _state.value.copy(status = message, error = message) }

    companion object {
        private const val TAG = "GlimpseBle"
        private const val MAX_PENDING_FRAMES = 64
        val NAVIGATION_SERVICE_UUID: UUID = UUID.fromString("7e57a000-b50c-4b6a-9c57-40a54e8e1000")
        val PHONE_TO_DEVICE_UUID: UUID = UUID.fromString("7e57a001-b50c-4b6a-9c57-40a54e8e1000")
        val DEVICE_TO_PHONE_UUID: UUID = UUID.fromString("7e57a002-b50c-4b6a-9c57-40a54e8e1000")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
