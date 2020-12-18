package com.dadachen.isitp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import kotlin.concurrent.thread

class IMUCollector(private val context: Context, private val modulePartial: (FloatArray) -> Unit) {
    private val gyro = FloatArray(3)
    private val acc = FloatArray(3)
    private val rotVector = FloatArray(4)
    private lateinit var sensorManager: SensorManager
    private var rotVSensor: Sensor? = null
    private var accVSensor: Sensor? = null
    private var gyroVSensor: Sensor? = null
    private val data = Array(6) {
        FloatArray(FRAME_SIZE)
    }

    private val currentLoc = floatArrayOf(0f, 0f)

    private enum class Status {
        Running, Idle
    }

    fun start() {
        initSensor()
        status = Status.Running
        thread(start = true) {
            var index = 0
            while (index < 192) {
                fillData(index++)
                Thread.sleep(FREQ_INTERVAL)
            }
            //check gesture and init estimation module by it
            checkGestureAndSwitchModule()
            index = 0
            while (status == Status.Running) {
                if (index == FRAME_SIZE) {
                    //check gesture but not changing estimation module
                    checkGesture()
                    //estimation by using 200 frames IMU-sensor
                    estimate()
                    //next step reset offset to zero
                    index = 0
                } else if (index % STEP == 0) {
                    //note index is always more than 1
                    estimate(index)
                }
                fillData(index++)
                Thread.sleep(FREQ_INTERVAL)
            }
        }
    }

    private fun fillData(index: Int) {
        data[0][index] = acc[0]
        data[1][index] = acc[1]
        data[2][index] = acc[2]
        data[3][index] = gyro[0]
        data[4][index] = gyro[1]
        data[5][index] = gyro[2]
    }

    private fun checkGestureAndSwitchModule() {
        checkGesture()
        val modulePath = when (gestureType) {
            GestureType.Hand -> {
                //need to be replaced
                "resnet.pt"
            }
            GestureType.Pocket -> {
                "resnet.pt"
            }
        }
        module = Module.load(Utils.assetFilePath(context, modulePath))

    }

    private fun checkGesture() {
        val tData = FloatArray(192 * 6)
        for (i in 0 until 6) {
            data[i].copyInto(tData, i * 192, 0, 192)
        }
        val gestureClassifier = GestureClassifier(Utils.assetFilePath(context, "mobile_model.pt"))
        gestureType = gestureClassifier.forward(tData)
        gestureTypeListener(gestureType)
    }

    fun stop() {
        status = Status.Idle
        stopSensor()
    }

    private val filters = Array(6) {
        IMULowPassFilter(FilterConstant.para)
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private lateinit var module: Module
    private fun estimate(offset: Int = 0) {
        //low-pass filter need parameters from MatLab
        //note: copy data in the main thread is so important,
        //please do not copy data in the coroutineScope
        val tData = data.copyOf()
        coroutineScope.launch {
            val tempoData = FloatArray(DATA_SIZE)
            tData.forEachIndexed { index, floatArray ->
                //low-pass filters are muted.
//                filters[index].filter(floatArray).copyInto(tempoData, index * FRAME_SIZE)
                if (offset > 0) {
                    floatArray.copyInto(tempoData, index * FRAME_SIZE, offset, floatArray.size)
                    floatArray.copyInto(
                        tempoData,
                        index * FRAME_SIZE + floatArray.size - offset,
                        0,
                        offset
                    )
                } else {
                    floatArray.copyInto(tempoData, index * FRAME_SIZE)
                }
            }

            val tensor = Tensor.fromBlob(tempoData, longArrayOf(1, 6, 200))
            val res = module.forward(IValue.from(tensor)).toTensor().dataAsFloatArray
            //output res for display on UI
            calculateDistance(res)
            modulePartial(currentLoc)

        }
    }

    private fun calculateDistance(res: FloatArray) {
        currentLoc[0] += res[0] * V_INTERVAL
        currentLoc[1] += res[1] * V_INTERVAL
    }

    private var status = Status.Idle
    private var gestureType = GestureType.Hand
    private val rotl = object : SensorEventListener {
        override fun onSensorChanged(p0: SensorEvent?) {
            rotVector[0] = p0!!.values[0]
            rotVector[1] = p0.values[1]
            rotVector[2] = p0.values[2]
            rotVector[3] = p0.values[3]

        }

        override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
            Log.d("imu", "rot accuracy changed")
        }
    }
    private val gyrol = object : SensorEventListener {
        override fun onSensorChanged(p0: SensorEvent?) {
            gyro[0] = p0!!.values[0]
            gyro[1] = p0.values[1]
            gyro[2] = p0.values[2]
        }

        override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
            Log.d("imu", "gyro accuracy changed")
        }
    }
    private val accl = object : SensorEventListener {
        override fun onSensorChanged(p0: SensorEvent?) {
            acc[0] = p0!!.values[0]
            acc[1] = p0.values[1]
            acc[2] = p0.values[2]
        }

        override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
            Log.d("imu", "acc accuracy changed")
        }
    }

    private fun initSensor() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotVSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        accVSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroVSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        sensorManager.registerListener(rotl, rotVSensor, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(accl, accVSensor, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(gyrol, gyroVSensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    private lateinit var gestureTypeListener: (GestureType) -> Unit
    fun setGestureTypeChangeListener(listener: (GestureType) -> Unit) {
        gestureTypeListener = listener
    }

    private fun resetSensor(){
        stopSensor()
        sensorManager.registerListener(rotl, rotVSensor, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(accl, accVSensor, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(gyrol, gyroVSensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    private fun stopSensor() {
        sensorManager.unregisterListener(accl)
        sensorManager.unregisterListener(gyrol)
        sensorManager.unregisterListener(rotl)
    }

    companion object {
        const val FRAME_SIZE = 200
        const val DATA_SIZE = 6 * 200
        const val FREQ_INTERVAL = 5L
        const val STEP = 10
        const val V_INTERVAL = 1f / STEP
    }
}