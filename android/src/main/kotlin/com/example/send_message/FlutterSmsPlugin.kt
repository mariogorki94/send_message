package com.example.send_message

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.RECEIVER_EXPORTED
import androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

class PendingSent {
    var message: String
    var recipients: String
    var result: Result

    constructor(message: String, recipients: String, result: Result) {
        this.message = message
        this.recipients = recipients
        this.result = result
    }
}

class FlutterSmsPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.RequestPermissionsResultListener, BroadcastReceiver() {
    private lateinit var mChannel: MethodChannel
    private var activity: Activity? = null
    private val REQUEST_CODE_SEND_SMS = 205
    private val REQUEST_CODE_PERMISSION = 206
    private val SENT_INTENT = "SMS_SENT_ACTION"
    private var pendingSent: PendingSent? = null

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activity?.registerReceiver(this, IntentFilter(SENT_INTENT), RECEIVER_EXPORTED)
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        setupCallbackChannels(flutterPluginBinding.binaryMessenger)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        teardown()
    }

    private fun setupCallbackChannels(messenger: BinaryMessenger) {
        mChannel = MethodChannel(messenger, "send_message")
        mChannel.setMethodCallHandler(this)
    }

    private fun teardown() {
        mChannel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "sendSMS" -> {
                val message = call.argument<String?>("message") ?: ""
                val recipients = call.argument<String?>("recipients") ?: ""
                val sendDirect = call.argument<Boolean?>("sendDirect") ?: false
                sendSMS(result, recipients, message, sendDirect)
            }

            "canSendSMS" -> result.success(canSendSMS())
            else -> result.notImplemented()
        }
    }

    private fun checkSmsPermission(): Boolean {
        return activity?.let {
            ActivityCompat.checkSelfPermission(
                it,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED
        } ?: false
    }

    private fun requestSmsPermission() {
        activity?.let {
            ActivityCompat.requestPermissions(
                it,
                arrayOf(Manifest.permission.SEND_SMS),
                REQUEST_CODE_PERMISSION
            )
        }
    }

    @TargetApi(Build.VERSION_CODES.ECLAIR)
    private fun canSendSMS(): Boolean {
        val currentActivity = activity ?: return false

        if (!currentActivity.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            return false
        val intent = Intent(Intent.ACTION_SENDTO)
        intent.data = Uri.parse("smsto:")
        val activityInfo =
            intent.resolveActivityInfo(currentActivity.packageManager, intent.flags.toInt())
        return !(activityInfo == null || !activityInfo.exported)
    }

    private fun sendSMS(result: Result, phones: String, message: String, sendDirect: Boolean) {
        if (sendDirect) {
            if (checkSmsPermission()) {
                sendSMSDirect(result, phones, message)
            } else {
                pendingSent = PendingSent(message, phones, result)
                requestSmsPermission()
            }
        } else {
            sendSMSDialog(result, phones, message)
        }
    }

    private fun sendSMSDirect(result: Result, phones: String, message: String) {
        val currentActivity = activity ?: run {
            result.error("no_activity", "Activity is not available", null)
            return
        }

        // SmsManager is android.telephony
        val sentIntent = PendingIntent.getBroadcast(currentActivity, 0, Intent(SENT_INTENT), PendingIntent.FLAG_IMMUTABLE)

        val mSmsManager = activity?.getSystemService(SmsManager::class.java) ?: run {
            result.error("no_sms_manager", "SmsManager is not available", null)
            return
        }
        val numbers = phones.split(";")

        for (num in numbers) {
            Log.d("Flutter SMS", "msg.length() : " + message.toByteArray().size)
            if (message.toByteArray().size > 80) {
                val partMessage = mSmsManager.divideMessage(message)
                mSmsManager.sendMultipartTextMessage(num, null, partMessage, null, null)
            } else {
                mSmsManager.sendTextMessage(num, null, message, sentIntent, null)
            }
        }

        pendingSent = PendingSent(message, phones, result)
    }

    private fun sendSMSDialog(result: Result, phones: String, message: String) {
        val currentActivity = activity ?: run {
            result.error("no_activity", "Activity is not available", null)
            return
        }

        val intent = Intent(Intent.ACTION_SENDTO)
        intent.data = Uri.parse("smsto:$phones")
        intent.putExtra("sms_body", message)
        intent.putExtra(Intent.EXTRA_TEXT, message)
        currentActivity.startActivityForResult(intent, REQUEST_CODE_SEND_SMS)
        result.success("SMS Sent!")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode == REQUEST_CODE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingSent?.let {
                    sendSMSDirect(it.result, it.recipients, it.message)
                    pendingSent = null
                }
                return true
            } else {
                pendingSent?.let {
                    it.result.error("permission_denied", "Permission denied", null)
                    pendingSent = null
                }
                return true
            }
        }
        return false
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (resultCode) {
            Activity.RESULT_OK -> {
                pendingSent?.let {
                    it.result.success("SMS Sent!")
                    pendingSent = null
                }
            }

            else -> {
                pendingSent?.let {
                    it.result.error("sms_failed", "SMS failed", null)
                    pendingSent = null
                }
            }
        }
    }
}