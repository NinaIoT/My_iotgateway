package com.weedscomm.iotgateway

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.GenericTypeIndicator
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.database.ktx.getValue
import com.google.firebase.ktx.Firebase
import info.mqtt.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttMessageListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.HashMap
import java.util.Timer
import java.util.TimerTask
import kotlin.random.Random


class MainActivity : AppCompatActivity() {
    // LOCAL HOST : Android emulator uses 10.0.2.2 as local host
    //private val serverIp = "tcp://10.0.2.2:1883"
    // broker.hivemq.com : broker.hivemq.com is an Internet-enabled mqtt broker server.
    //    private val serverIp = "tcp://broker.hivemq.com:1883"
    // TAG
    val TAG_DEBUG = "iot_study"
    // TOPIC
    val TOPIC_PUB = "/iot_study_data"
    val TOPIC_SUB = "/iot_study_cmd"

    lateinit var mqttclient: MqttAndroidClient

    //firebase realtime db
    private lateinit var database: FirebaseDatabase

    // Gateway - Things Communication Protocol
    // "M000C01T30"    -> MXXX things id | CXX command | TXX Threshold
    // "M001C02T70"    -> MXXX things id | CXX command | TXX Threshold 2

    // for Things
    val timer = Timer()
    val timerInterval = 10000L   // 10sec
    val thingsNum = 5
    var thingsArray = arrayOfNulls<Boolean>(thingsNum)
    var thingsThresholdArray = arrayOfNulls<Int>(thingsNum)

    enum class ThingRunType (val intValue:Int){
        CMD_STOP(0),
        CMD_RUN(1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initDatabase()
        initUI()
        initThings()
    }

    private fun initUI() {
        setContentView(R.layout.activity_main)

        val connectBtn = findViewById<Button>(R.id.connect_button)
        val publishMsgBtn = findViewById<Button>(R.id.publish_msg_button)
        val subscribeBtn = findViewById<Button>(R.id.subscribe_button)

        connectBtn.setOnClickListener {
            connectMqttServer()
        }

        publishMsgBtn.setOnClickListener {
            publishMessage()
        }

        subscribeBtn.setOnClickListener {
            subscribeMessage()
        }
    }

    private fun initMqtt() {
        val hostTextView = findViewById<EditText>(R.id.mqtt_host_ip)
        val hostString = "tcp://"+hostTextView.text.toString()+":1883"

        val infoHostTextView = findViewById<TextView>(R.id.info_host_textview)
        infoHostTextView.text = hostString

        if (::mqttclient.isInitialized) {
            if (mqttclient.isConnected) {
                mqttclient.disconnect()
            }
        }
        mqttclient = MqttAndroidClient(applicationContext, hostString, MqttClient.generateClientId())
    }

    private fun connectMqttServer() {
        initMqtt()

        if ( !::mqttclient.isInitialized ) {
            return
        }

        val mqttConnectOptions = MqttConnectOptions()
        mqttConnectOptions.isAutomaticReconnect = false
        mqttConnectOptions.isCleanSession = true
        mqttConnectOptions.connectionTimeout = 3
        mqttConnectOptions.keepAliveInterval = 60

        try {
            mqttclient.connect(
                mqttConnectOptions, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d(TAG_DEBUG, "onSuccess: Successfully connected to the broker")

                        runOnUiThread {
                            val infoHostTextView = findViewById<TextView>(R.id.info_host_textview)
                            val infoHost = infoHostTextView.text.toString()
                            infoHostTextView.text = infoHost + " connected"
                            infoHostTextView.setTextColor(Color.parseColor("#008800"))
                        }
                        val disconnectBufferOptions = DisconnectedBufferOptions()
                        disconnectBufferOptions.isBufferEnabled = true
                        disconnectBufferOptions.bufferSize = 100
                        disconnectBufferOptions.isPersistBuffer = false
                        disconnectBufferOptions.isDeleteOldestMessages = false
                        mqttclient.setBufferOpts(disconnectBufferOptions)

                    }
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.d(TAG_DEBUG, "onFailure: ${exception}")

                        runOnUiThread {
                            // UI 코드를 이 안으로 옮긴다.
                            val infoHostTextView = findViewById<TextView>(R.id.info_host_textview)
                            val infoHost = infoHostTextView.text.toString()
                            infoHostTextView.setTextColor(Color.parseColor("#FF0000"))

                            infoHostTextView.text = infoHost + " connect fail"
                        }
                    }
                }
            )
        } catch (ex: MqttException) {
            ex.printStackTrace()
        }
    }

    private fun subscribeMessage() {
        val topicEditTextView = findViewById<EditText>(R.id.subscribe_topic_edittext)
        val subscribeTopic = topicEditTextView.text.toString()
        try {
            mqttclient.subscribe(subscribeTopic, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    Log.d(TAG_DEBUG, "onSuccess: Subscribe ${subscribeTopic}")
                }
                override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable) {
                }
            })

            mqttclient.subscribe(subscribeTopic, 1,
                IMqttMessageListener { topic, message -> // message Arrived!
                    Log.d(TAG_DEBUG, ("Message: " + topic + " : " + "${message}"))
                    parseMqttMessage(message.toString())

                    writeDatabase(topic, "${message}")

                })
        } catch (ex: MqttException) {
            System.err.println("Exception whilst subscribing")
            ex.printStackTrace()
        }
    }

    private fun publishMessage() {
        val topicEditTextView = findViewById<EditText>(R.id.publish_topic_edittext)
        val publishMsgTextView = findViewById<EditText>(R.id.publish_msg_textview)

        val topic = topicEditTextView.text.toString()
        val message = publishMsgTextView.text.toString()
        //mqttclient.publish(TOPIC_PUB, MqttMessage(message.toByteArray()))
        mqttclient.publish(topic, MqttMessage(message.toByteArray()))

        writeDatabase()
    }

    private fun initDatabase() {
        database = Firebase.database

    }

    // for test
    private fun writeDatabase() {
        if (!::database.isInitialized) {
            return
        }
        val myRef = database.getReference("message")
        myRef.setValue("Hello, Nina!")
    }

    private fun writeDatabase(topic:String, message:String) {
        if (!::database.isInitialized) {
            return
        }

        val myRef = database.getReference(topic)

//        myRef.setValue(message)
        myRef.push().setValue(message)

        myRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                // This method is called once with the initial value and again
                // whenever data at this location is updated.
                for ( childSnapshot in dataSnapshot.children) {

                    val messageMap: Map<String,Any>? = childSnapshot.getValue() as? Map<String,Any>

                    if ( messageMap != null ) {
                        Log.d(TAG_DEBUG, "Key is: $childSnapshot.key Value is: $messageMap")
                    }
                    val messageString:String? = childSnapshot.getValue() as? String

                    if ( messageString != null ) {
                        Log.d(TAG_DEBUG, "Key is: $childSnapshot.key Value is: $messageString")

                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Failed to read value
                Log.w(TAG_DEBUG, "Failed to read value.", error.toException())
            }
        })
    }

    private fun initThings() {
        thingsArray.fill(false)
        thingsThresholdArray.fill(0)
        // interval 10sec
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                // 타이머 동작 시 수행할 작업
                if ( !::mqttclient.isInitialized ) {
                    return
                }
                if ( !mqttclient.isConnected ) {
                    return
                }

                for ( id in 1..thingsNum) {
                    val arrayIdx = id - 1
                    if ( thingsArray[arrayIdx] == true) {
                        // generate random value

                        val sensorData01 = Random.nextInt(0,100)
                        val sensorData02 = Random.nextInt(0,100)
                        val sensorData03 = Random.nextInt(0,100)

                        val receiveValue = String.format("%02d:%02d:%02d", sensorData01, sensorData02, sensorData03)
                        receiveDataFromThings(id, receiveValue)
                    }
                }
            }
        }, 0, timerInterval) // wait 0sec, loop 10sec

    }

    // mqtt message parsing
    private fun parseMqttMessage( msg:String ) {
        // validate mqtt message
        if (!validtaeMqttMessage(msg)) {
            return
        }

        val thingsId = msg.substring(1, 4).toInt()
        val command = msg.substring(5,7).toInt()
        val threshold = msg.substring(8,10).toInt()

        setCommandForThings(thingsId, command, threshold)
    }

    // Sending commands to each thing.
    private fun setCommandForThings( thingsId:Int, command:Int, threshold:Int ) {
        val arrayIdx = thingsId - 1

        if ( arrayIdx < thingsArray.count() && arrayIdx >= 0) {
            if ( command == ThingRunType.CMD_STOP.intValue ) {
                thingsArray[arrayIdx] = false
            }
            else if ( command == ThingRunType.CMD_RUN.intValue ) {
                thingsArray[arrayIdx] = true
            }
            else {
                thingsArray[arrayIdx] = false
            }

            thingsThresholdArray[arrayIdx] = threshold
        }
        Log.w(TAG_DEBUG, "thingsId[$thingsId] command[$command]")
    }

    // Validate the forwarded message format
    // It is common to use CRC (Cyclical Redundancy Check) or the like.
    private fun validtaeMqttMessage( msg:String ):Boolean {
        Log.w(TAG_DEBUG, "validtaeMqttMessage : ${msg}")

        if ( msg.count() != 10 ) {
            return false
        }

        if ( msg.substring(0, 1) != "M" || msg.substring(4, 5) != "C" || msg.substring(7, 8) != "T" ) {
            return false
        }
        return true
    }

    // Function to receive when Sensor value is sent from Things
    private fun receiveDataFromThings(thingsId:Int, data:String ) {
        Log.w(TAG_DEBUG, "receiveDataFromThings :${thingsId} ${data}")

        val dataParts = data.split(":")

        if (dataParts.count() < 3 ) {
            return
        }
        updateRealTimeData(thingsId, dataParts[0].toInt(), dataParts[1].toInt(), dataParts[2].toInt());
    }

    fun getCurrentDateTimeAsString(): String {
        val currentDateTime = Calendar.getInstance().time
        val formatter = SimpleDateFormat("yyyyMMddHHmmss")
        return formatter.format(currentDateTime)
    }

    private fun updateRealTimeData(thingsId:Int, value01:Int, value02:Int, value03:Int) {
        if (!::database.isInitialized) {
            return
        }

        val deviceId = thingsId - 1

        if ( deviceId >= thingsThresholdArray.size || deviceId < 0 ) {
            Log.e(TAG_DEBUG, "deviceId error")
            return
        }
        val threshold = thingsThresholdArray[deviceId] ?: 0

        if ( value01 > threshold || value02 > threshold || value03 > threshold ) {
            val iotData = HashMap<String, String>()
            iotData["time"] = getCurrentDateTimeAsString()
            iotData["x"] = value01.toString()
            iotData["y"] = value02.toString()
            iotData["z"] = value03.toString()

            Log.w(TAG_DEBUG, "updateRealTimeData :${thingsId} ${iotData}")

            database.getReference().child("things").child(thingsId.toString()).push()
                .setValue(iotData)
        }
    }
}