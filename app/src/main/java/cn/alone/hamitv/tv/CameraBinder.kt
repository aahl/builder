package cn.alone.hamitv.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Client for the camera binder exported by com.xiaomi.smarthome.tv.
 *
 * The service is exported without a permission, so a normal app can bind to it
 * and drive the camera picture-in-picture window. The real AIDL interface is
 * com.xiaomi.smarthome.camera.panel.remote.ICameraBinder (NOT ITvCameraApi,
 * which is only an internal Kotlin wrapper). We talk to it with raw
 * transactions so no vendored stubs or Parcelables are required.
 *
 * Transaction codes were read from ICameraBinder$Stub in the installed APK:
 *   1 getApiLevel()            4 getAllCamera(cb)       5 isExistCameraFloatWindow()
 *   6 getCurrentFloatCamera(cb) 7 setFloatWindowState()  8 startFloatWindow(did)
 *   9 closeFloatWindow()       10 getSwitchCameraType(did)
 */
class CameraBinder(private val context: Context) {

    data class Camera(val did: String, val roomName: String)

    private val lock = Any()
    private var binder: IBinder? = null
    private var connection: ServiceConnection? = null

    /** Drops the binding so the next call reconnects. */
    fun unbind() = synchronized(lock) { unbindLocked() }

    fun apiLevel(): Int = call(TRANSACTION_API_LEVEL) { it.readInt() }

    fun cameras(): List<Camera> {
        val result = ArrayList<Camera>()
        val latch = CountDownLatch(1)
        var message: String? = null
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                message = data.readString()
                // createTypedArrayList: int count, then (int present, did, roomName) per item.
                val count = data.readInt()
                repeat(count.coerceAtLeast(0)) {
                    if (data.readInt() != 0) {
                        result.add(Camera(data.readString().orEmpty(), data.readString().orEmpty()))
                    }
                }
                reply?.writeNoException()
                latch.countDown()
                return true
            }
        }
        call(TRANSACTION_GET_ALL_CAMERA) { it.writeStrongBinder(callback) }
        // The service answers synchronously in practice, but never hang the caller.
        latch.await(3, TimeUnit.SECONDS)
        if (latch.count > 0) throw IllegalStateException(message ?: "camera list timed out")
        return result
    }

    fun startFloatWindow(did: String) {
        require(did.isNotBlank()) { "did is empty" }
        call(TRANSACTION_START_FLOAT_WINDOW) { it.writeString(did) }
    }

    fun closeFloatWindow() = call(TRANSACTION_CLOSE_FLOAT_WINDOW) {}

    fun isFloatWindowVisible(): Boolean =
        call(TRANSACTION_IS_FLOAT_WINDOW) { it.readInt() != 0 }

    fun setFloatWindowState(visible: Boolean, move: Boolean) =
        call(TRANSACTION_SET_FLOAT_STATE) {
            it.writeInt(if (visible) 1 else 0)
            it.writeInt(if (move) 1 else 0)
        }

    private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val remote = ensureBound()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            if (!remote.transact(code, data, reply, 0)) {
                throw IllegalStateException("camera service rejected transaction $code")
            }
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun call(code: Int, write: (Parcel) -> Unit) {
        call(code, write) { }
    }

    private fun ensureBound(): IBinder {
        synchronized(lock) {
            binder?.let { if (it.isBinderAlive) return it }
            unbindLocked()

            val latch = CountDownLatch(1)
            var connected: IBinder? = null
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    connected = service
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    binder = null
                }
            }
            val intent = Intent(ACTION)
                .setPackage(PACKAGE)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            val accepted = runCatching {
                context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!accepted) {
                // Fall back to the explicit component in case the filter changed.
                val explicit = Intent().setComponent(ComponentName(PACKAGE, SERVICE))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                if (!context.bindService(explicit, conn, Context.BIND_AUTO_CREATE)) {
                    throw IllegalStateException("cannot bind $PACKAGE")
                }
            }
            if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) || connected == null) {
                runCatching { context.unbindService(conn) }
                throw IllegalStateException("camera service did not respond")
            }
            connection = conn
            binder = connected
            return connected!!
        }
    }

    private fun unbindLocked() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        binder = null
    }

    companion object {
        const val PACKAGE = "com.xiaomi.smarthome.tv"
        private const val SERVICE = "com.xiaomi.smarthome.tv/com.xiaomi.smarthome.camera.remote.RemoteCameraService"
        private const val ACTION = "com.xiaomi.camera.remotecameraservice.aidl"
        private const val DESCRIPTOR = "com.xiaomi.smarthome.camera.panel.remote.ICameraBinder"
        private const val CALLBACK_DESCRIPTOR = "com.xiaomi.smarthome.camera.panel.remote.ICameraInfoCallback"

        private const val TRANSACTION_API_LEVEL = 1
        private const val TRANSACTION_GET_ALL_CAMERA = 4
        private const val TRANSACTION_IS_FLOAT_WINDOW = 5
        private const val TRANSACTION_SET_FLOAT_STATE = 7
        private const val TRANSACTION_START_FLOAT_WINDOW = 8
        private const val TRANSACTION_CLOSE_FLOAT_WINDOW = 9

        private const val BIND_TIMEOUT_MS = 4000L
    }
}
