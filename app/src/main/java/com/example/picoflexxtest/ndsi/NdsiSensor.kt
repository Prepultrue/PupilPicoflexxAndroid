package com.example.picoflexxtest.ndsi

import android.util.Log
import com.example.picoflexxtest.recentEvents
import com.example.picoflexxtest.sendMultiPart
import com.example.picoflexxtest.zmq.NdsiManager
import com.example.picoflexxtest.zmq.mapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.zeromq.SocketType
import org.zeromq.ZMQ
import zmq.Msg
import zmq.msg.MsgAllocator
import java.nio.ByteBuffer
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


abstract class NdsiSensor(
    val sensorType: String,
    val sensorUuid: String,
    val sensorName: String,
    protected val manager: NdsiManager
) {
    private val TAG = NdsiSensor::class.java.simpleName

    private lateinit var data: ZMQ.Socket
    protected var dataSequence = 0
    private lateinit var dataUrl: String
    private lateinit var note: ZMQ.Socket
    private lateinit var noteUrl: String
    private var noteSequence = 0
    private lateinit var cmd: ZMQ.Socket
    private lateinit var cmdUrl: String
    protected abstract val width: Int
    protected abstract val height: Int

    protected open val changedControls = HashSet<Any>()
    protected val controls: MutableMap<String, ControlInfo<*>> = hashMapOf()
    protected val controlsByChangeKey: MutableMap<Any, MutableList<ControlInfo<*>>> = hashMapOf()

    open fun ping(): Boolean {
        return true
    }

    fun setupSockets() {
        Log.d(TAG, "setupSockets() sensorUuid=$sensorUuid")

        val publicEndpoint = this.manager.currentListenAddress
            ?: throw IllegalStateException("Called setupSockets with no manager.currentListenAddress!")
        val genericUrl = "tcp://*:*"
        Log.i(TAG, "publicEndpoint=$publicEndpoint")

        val old = ArrayList<ZMQ.Socket>()
        if (this::data.isInitialized) {
            old.add(this.data)
            old.add(this.note)
            old.add(this.cmd)
        }

        this.manager.bind(SocketType.PUB, genericUrl, publicEndpoint, ::data, ::dataUrl)
        this.manager.bind(SocketType.PUB, genericUrl, publicEndpoint, ::note, ::noteUrl)
        this.manager.bind(SocketType.PULL, genericUrl, publicEndpoint, ::cmd, ::cmdUrl)

        this.data.base().setSocketOpt(zmq.ZMQ.ZMQ_MSG_ALLOCATOR, object : MsgAllocator {
            override fun allocate(size: Int): Msg {
                Log.d(TAG, "(data) Allocating buffer of size $size")
                // FIXME do we initialize Msg elsewhere ourselves?
                return Msg(ByteBuffer.allocateDirect(size))
            }
        })

        old.forEach {
            it.close()
        }
    }

    open fun unlink() {
        this.data.close()
        this.note.close()
        this.cmd.close()
    }

    open fun pollCmdSocket() {
        this.cmd.recentEvents().forEach {
            Log.d(TAG, "cmdSocket recent event = $it")

            val command = mapper.readValue<SensorCommand>(it[1])
            when (command.action) {
                "refresh_controls" -> refreshControls()
                "set_control_value" -> when (command.value) {
                    is Int -> setControlValue(command.controlId!!, command.value)
                    is Long -> setControlValue(command.controlId!!, command.value)
                    is Boolean -> setControlValue(command.controlId!!, command.value)
                    is String -> setControlValue(command.controlId!!, command.value)
                    null -> Log.w(TAG, "Unexpected null value in $command")
                    else -> Log.w(TAG, "Unknown value type ${command.value.javaClass.name}")
                }
                else -> Log.w(TAG, "Unknown command on cmd socket: $command")
            }
        }
    }

    abstract fun hasFrame(): Boolean

    abstract fun publishFrame()

    protected fun sendFrame(flag: Int, timestamp: Double, extra: Int, data: ByteArray) {
        this.sendFrame(
            NdsiHeader(
                flag,
                this.width,
                this.height,
                this.dataSequence++,
                timestamp,
                extra
            ), data
        )
    }

    protected fun sendFrame(header: NdsiHeader, data: ByteArray) {
        header.dataLength = data.size

        this.data.sendMultiPart(
            this.sensorUuid.toByteArray(),
            header.encode(),
            data
        )
    }

    fun handleNotification() {
        TODO("Implement handleNotification")
    }

    fun refreshControls() {
        controls.values.forEach {
            this.sendControlState(it)
        }
    }

    fun resetAllControlValues() {
        TODO("Implement resetAllControlValues")
    }

    fun resetControlValue(controlId: String) {
        TODO("Implement resetControlValue")
    }

    fun <T : Any> setControlValue(controlId: String, value: T) {
        val control = this.controls[controlId]
        if (control == null) {
            Log.w(TAG, "Tried to set value on unknown control! control=$controlId value=$value")
            return
        }

        assert(control.type.java.isAssignableFrom(value::class.java))
        @Suppress("UNCHECKED_CAST")
        val typedControl = control as ControlInfo<T>

        if (typedControl.setter == null) {
            Log.w(TAG, "Control ${control.controlId} uk=${control.updateKey} unexpectedly had a null setter")
            return
        }

        typedControl.setter.invoke(typedControl, value)
        this.sendControlState(control)
    }

    open fun sensorAttachJson() = SensorAttach(
        this.sensorName,
        this.sensorUuid,
        this.sensorType,
        this.noteUrl,
        this.cmdUrl,
        this.dataUrl
    )

    protected fun <T : Any> sendControlState(control: ControlInfo<T>) {
        note.sendMultiPart(
            this.sensorUuid.toByteArray(),
            mapper.writeValueAsBytes(
                UpdateControlMessage(
                    subject = "update",
                    controlId = control.controlId!!,
                    seq = this.noteSequence,
                    changes = control.getter!!(control)
                )
            )
        )

        this.noteSequence += 1
    }

    fun sendUpdatedControls() {
        this.changedControls.toList()
        val changed = this.changedControls.clone() as Set<*>
        this.changedControls.removeAll(changed)
        changed.forEach { changeKey ->
            this.controlsByChangeKey[changeKey]?.forEach { control ->
                this.sendControlState(control)
            }
        }
    }

    protected fun registerStringMapControl(
        controlId: String, caption: String, default: Int,
        values: List<String>,
        setter: (Int) -> Unit
    ) = registerControl(
        controlId,
        getter = {
            ControlChanges(
                value = this.value,
                def = default,
                caption = caption,
                map = values.mapIndexed { idx, uc ->
                    ControlEnumOptions(idx, uc)
                }
            )
        },
        setter = { newValue ->
            if (newValue < 0 || newValue >= values.size) {
                Log.w(TAG, "Attempted to set an invalid index '$newValue' on control $controlId")
                return@registerControl
            }

            setter(newValue)
        },
        value = default
    )

    protected fun registerIntControl(
        controlId: String, caption: String, default: Int, min: Int = 0, max: Int = 0, res: Int = 1,
        getter: ((ControlChanges) -> Unit)? = null,
        setter: ((Int) -> Unit)? = null
    ) = registerControl(
        controlId,
        getter = {
            return@registerControl ControlChanges(
                value = this.value,
                min = min,
                max = max,
                res = res,
                def = default,
                dtype = "integer",
                caption = caption
            ).also {
                getter?.invoke(it)
            }
        },
        setter = { setter?.invoke(it) },
        value = default
    )

    protected fun registerBooleanControl(
        controlId: String, caption: String, default: Boolean = false,
        getter: ((ControlChanges) -> Unit)? = null,
        setter: (Boolean) -> Unit
    ) = registerControl(
        controlId,
        getter = {
            return@registerControl ControlChanges(
                value = this.value,
                def = default,
                dtype = "bool",
                caption = caption
            ).also {
                getter?.invoke(it)
            }
        },
        setter = { setter(it) },
        value = default
    )

    protected inline fun <reified T : Any> registerControl(
        controlId: String?,
        noinline getter: (ControlInfo<T>.() -> ControlChanges)?,
        noinline setter: (ControlInfo<T>.(T) -> Unit)?,
        value: T,
        updateKey: Any? = null
    ) = ControlInfo(
        controlId,
        T::class,
        getter,
        setter,
        value,
        updateKey
    )

    inner class ControlInfo<T : Any>(
        val controlId: String?,
        val type: KClass<T>,
        val getter: (ControlInfo<T>.() -> ControlChanges)?,
        val setter: (ControlInfo<T>.(T) -> Unit)?,
        var value: T,
        updateKey: Any? = null
    ) {
        // If controlId is null, updateKey MUST be non-null
        val updateKey: Any = updateKey ?: this.controlId!!

        init {
            if (this@NdsiSensor.controls.containsKey(this.controlId)) {
                Log.e(TAG, "There's already a control registered with id=${this.controlId}!")
            }

            if (this.controlId != null) {
                this@NdsiSensor.controls[this.controlId] = this
                this@NdsiSensor.controlsByChangeKey.putIfAbsent(this.updateKey, arrayListOf(this))
                    ?.add(this)
            }
        }

        operator fun getValue(thisRef: NdsiSensor, property: KProperty<*>): T {
            return this.value
        }

        operator fun setValue(thisRef: NdsiSensor, property: KProperty<*>, value: T) {
            if (this.value != value) {
                this@NdsiSensor.changedControls.add(this.updateKey)

                this.value = value
            }
        }
    }
}
