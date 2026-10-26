package org.yuzu.yuzu_emu.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.os.IBinder
import android.os.Parcel

/** Toggles the whole-device override used by the GamePad NS 16:9 mode. */
object GamePadNsDisplaySizeController {
    // The Pad reports its natural display in portrait coordinates. Keep the physical orientation
    // portrait at the WM layer; the app's explicit landscape request then exposes 3840x2160.
    private const val TARGET_WIDTH = GamePadNsDisplayMode.FORCED_WIDTH
    private const val TARGET_HEIGHT = GamePadNsDisplayMode.FORCED_HEIGHT
    private const val WINDOW_DESCRIPTOR = "android.view.IWindowManager"
    private const val SET_FORCED_SIZE = IBinder.FIRST_CALL_TRANSACTION + 6
    private const val CLEAR_FORCED_SIZE = IBinder.FIRST_CALL_TRANSACTION + 7

    fun toggle(context: Context): String {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) return "请先通过 ADB 授权分辨率切换"

        return runCatching {
            val bridge = Bridge()
            val initial = bridge.initialSize()
            val current = bridge.baseSize()
            if (current != initial) bridge.clearForcedSize() else bridge.setForcedSize()
            val verified = bridge.baseSize()
            if (current != initial && verified != initial) error("恢复物理分辨率校验失败")
            if (current == initial && verified != Dimensions(TARGET_WIDTH, TARGET_HEIGHT)) {
                error("3840×2160 分辨率校验失败：$verified")
            }
            if (current != initial) "已恢复设备原始分辨率" else "已切换整机分辨率为 3840×2160"
        }.getOrElse { "分辨率切换失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private data class Dimensions(val width: Int, val height: Int) {
        override fun toString(): String = "${width}x$height"
    }

    private class Bridge {
        private var binder: IBinder? = null
        private var manager: Any? = null

        fun initialSize(): Dimensions = read("getInitialDisplaySize")
        fun baseSize(): Dimensions = read("getBaseDisplaySize")
        fun setForcedSize() = transact(SET_FORCED_SIZE, TARGET_WIDTH, TARGET_HEIGHT)
        fun clearForcedSize() = transact(CLEAR_FORCED_SIZE)

        private fun read(methodName: String): Dimensions {
            val point = Point()
            manager().javaClass.getMethod(
                methodName,
                Int::class.javaPrimitiveType,
                Point::class.java
            ).invoke(manager(), 0, point)
            return Dimensions(point.x, point.y)
        }

        private fun manager(): Any {
            manager?.let { return it }
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            binder = getService.invoke(null, "window") as? IBinder
                ?: error("无法取得 WindowManager 服务")
            val stub = Class.forName("android.view.IWindowManager\$Stub")
            manager = stub.getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
                ?: error("无法连接 WindowManager 服务")
            return manager!!
        }

        private fun transact(code: Int, vararg size: Int) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(WINDOW_DESCRIPTOR)
                data.writeInt(0)
                size.forEach(data::writeInt)
                val windowBinder = binder ?: run { manager(); binder!! }
                if (!windowBinder.transact(code, data, reply, 0)) {
                    error("WindowManager 未处理分辨率请求")
                }
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
