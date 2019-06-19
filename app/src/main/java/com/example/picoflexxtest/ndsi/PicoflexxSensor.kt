package com.example.picoflexxtest.ndsi

import android.util.Log
import com.example.picoflexxtest.ndsi.picoflexx.LastCompressionInfo
import com.example.picoflexxtest.royale.RoyaleCameraDevice
import com.example.picoflexxtest.royale.RoyaleCameraException
import com.example.picoflexxtest.zmq.NdsiManager
import com.github.luben.zstd.Zstd
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

private data class PicoflexxData(
    val encoded: ByteArray,
    val timestamp: Long
)

class PicoflexxSensor(
    manager: NdsiManager,
    private val camera: RoyaleCameraDevice
) : NdsiSensor(
    "royale_full",
    UUID.nameUUIDFromBytes(camera.getCameraId().toByteArray()).toString(),
    "${camera.getCameraName()} - ${camera.getCameraId()}",
    manager
) {
    companion object {
        private val sharedScheduler = Executors.newSingleThreadScheduledExecutor()
        private val TAG = PicoflexxSensor::class.java.simpleName
        private const val CONTROL_USE_CASE = "usecase"
        private const val CONTROL_AUTO_EXPOSURE = "auto_exposure"
        private const val CONTROL_EXPOSURE_TIME = "exposure_time"
        private const val CONTROL_FRAME_RATE = "frame_rate"
    }

    private var futureExposure: ScheduledFuture<*>? = null
    private val dataQueue = ArrayBlockingQueue<PicoflexxData>(5)
    val useCases = camera.getUseCases()
    val cameraName = camera.getCameraName()
    val cameraId = camera.getCameraId()
    override val width = camera.getMaxSensorWidth()
    override val height = camera.getMaxSensorHeight()

    // We register controls with the registerControl delegate. Upon updating
    // any of the control's values, the control will be flagged as dirty and
    // will be updated next cycle in NdsiManager.
    private var currentExposure by registerControl(
        CONTROL_EXPOSURE_TIME,
        ::getExposureTimeControl,
        ::setExposureTimeControl,
        0
    )
    private var autoExposure by registerControl(
        CONTROL_AUTO_EXPOSURE,
        ::getAutoExposureControl,
        ::setAutoExposureControl,
        true
    )
    // Pseudo-control to trigger the exposure control to be updated
    private var minExposure by registerControl(
        null, null, null, 0, updateKey = CONTROL_EXPOSURE_TIME
    )
    // Pseudo-control to trigger the exposure control to be updated
    private var maxExposure by registerControl(
        null, null, null, 2000, updateKey = CONTROL_EXPOSURE_TIME
    )
    private var frameRate by registerControl(
        CONTROL_FRAME_RATE, ::getFrameRateControl, null, 2
    )
    private var maxFrameRate by registerControl(
        null, null, null, 2, updateKey = CONTROL_FRAME_RATE
    )
    private var currentUseCase by registerControl(
        CONTROL_USE_CASE,
        ::getUsecaseControl,
        ::setUsecaseControl,
        0
    )
    val lastCompressionData = LastCompressionInfo(0, 0, 0)
    val queueSize get() = this.dataQueue.size

    init {
        Log.i(TAG, "Camera getUseCases: ${this.useCases}")
        Log.i(TAG, "Camera getCameraName: ${this.cameraName}")
        Log.i(TAG, "Camera getCameraId: ${this.cameraId}")
        Log.i(TAG, "Camera getMaxSensorWidth: ${this.width}")
        Log.i(TAG, "Camera getMaxSensorHeight: ${this.height}")

        camera.startCapture()

        camera.addEncodedDepthDataCallback {
            try {
                dataQueue.add(PicoflexxData(it, System.currentTimeMillis())) // FIXME use timestamp from libroyale
                this.manager.notifySensorReady()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "$this: ${e.localizedMessage}")
                e.printStackTrace()
            }
        }
        camera.addExposureTimeCallback {
            this.currentExposure = it[1]
        }

        this.updateControlState()
    }

    override fun ping(): Boolean {
        try {
            val originalMode = this.autoExposure
            Log.i(TAG, "Set auto exposure=${this.camera.setExposureMode(false)}")
            Log.i(TAG, "Check same exp=${this.camera.setExposureTime(this.currentExposure.toLong())}")
            Log.i(TAG, "Set prev auto exposure=${this.camera.setExposureMode(originalMode)}")
        } catch (e: RoyaleCameraException) {
            if (e.code == 4100) { // DEVICE_IS_BUSY
                Log.w(TAG, "$this: Busy ${e.localizedMessage}")
                return true
            } else if (e.code == 1026 || e.code == 1028) { // DISCONNECTED or TIMEOUT
                Log.w(TAG, "$this: Disconnected ${e.localizedMessage}")
                return false
            }

            e.printStackTrace()
            return false
        }
        return true
    }

    private fun updateControlState() {
        this.currentUseCase = this.useCases.indexOf(this.camera.getCurrentUseCase())
        this.autoExposure = this.camera.getExposureMode()
        val limits = this.camera.getExposureLimits()
        this.frameRate = this.camera.getFrameRate()
        this.maxFrameRate = this.camera.getMaxFrameRate()
        this.minExposure = limits[0]
        this.maxExposure = limits[1]
    }

    protected fun getUsecaseControl(): ControlChanges {
        return ControlChanges(
            value = this.useCases.indexOf(this.camera.getCurrentUseCase()),
            def = 0,
            caption = "Use Case",
            map = this.useCases.mapIndexed { idx, uc ->
                ControlEnumOptions(idx, uc)
            }
        )
    }

    protected fun setUsecaseControl(value: Int) {
        if (value < 0 || value >= this.useCases.size) {
            Log.w(TAG, "Attempted to set an invalid index '$value'")
            return
        }

        this.camera.setUseCase(this.useCases[value])
        this.updateControlState()
    }

    protected fun getAutoExposureControl(): ControlChanges {
        return ControlChanges(
            value = this.camera.getExposureMode(),
            dtype = "bool",
            def = true,
            caption = "Auto exposure"
        )
    }

    protected fun getFrameRateControl(): ControlChanges {
        return ControlChanges(
            value = this.frameRate,
            min = 1,
            max = this.maxFrameRate,
            def = this.maxFrameRate,
            readonly = true,
            dtype = "integer",
            caption = "Frame rate"
        )
    }

    protected fun setAutoExposureControl(value: Boolean) {
        this.camera.setExposureMode(value)

        this.sendControlState(this.controls[CONTROL_EXPOSURE_TIME]!!)
        this.updateControlState()

        // Label exposure control as dirty to ensure the read only status is
        // correctly updated
        this.changedControls.add(CONTROL_EXPOSURE_TIME)
    }

    protected fun getExposureTimeControl(): ControlChanges {
        return ControlChanges(
            value = this.currentExposure,
            readonly = this.autoExposure,
            min = this.minExposure,
            max = this.maxExposure,
            def = this.maxExposure,
            res = 1,
            dtype = "integer",
            caption = "Exposure time"
        )
    }

    protected fun setExposureTimeControl(value: Int) {
        val futureExposure = this.futureExposure
        if (futureExposure != null) {
            futureExposure.cancel(false)
            this.futureExposure = null
        }
        this.futureExposure = sharedScheduler.schedule({
            this.camera.setExposureTime(value.toLong())
        }, 200, TimeUnit.MILLISECONDS)
    }

    override fun hasFrame() = !this.dataQueue.isEmpty()

    override fun publishFrame() {
        val data = dataQueue.poll(200, TimeUnit.MILLISECONDS)

        if (data == null) {
            Log.w(TAG, "$this: Timed out waiting for data")
            return
        }

        lateinit var compressed: ByteArray
        val compressTime = measureNanoTime {
            compressed = Zstd.compress(data.encoded, 1)
        }
        this.lastCompressionData.apply {
            compressedSize = compressed.size
            uncompressedSize = data.encoded.size
            timeMicros = compressTime / 1000
        }

        this.sendFrame(
            FLAG_ALL,
            data.timestamp / 1000.0,
            this.currentExposure,
            compressed
        )
    }

    override fun unlink() {
        super.unlink()

        this.camera.close()
    }
}
