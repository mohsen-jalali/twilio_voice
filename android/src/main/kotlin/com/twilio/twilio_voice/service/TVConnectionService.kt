package com.twilio.twilio_voice.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.*
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.twilio.twilio_voice.R
import com.twilio.twilio_voice.call.TVCallParameters
import com.twilio.twilio_voice.call.TVCallParametersImpl
import com.twilio.twilio_voice.messaging.FirebaseMessagingService
import com.twilio.twilio_voice.receivers.TVBroadcastReceiver
import com.twilio.twilio_voice.storage.Storage
import com.twilio.twilio_voice.storage.StorageImpl
import com.twilio.twilio_voice.types.BundleExtensions.getParcelableSafe
import com.twilio.twilio_voice.types.CompletionHandler
import com.twilio.twilio_voice.types.ContextExtension.appName
import com.twilio.twilio_voice.types.IntentExtension.getParcelableExtraSafe
import com.twilio.twilio_voice.types.TelecomManagerExtension.hasCallCapableAccount
import com.twilio.twilio_voice.types.TelecomManagerExtension.hasReadPhonePermission
import com.twilio.twilio_voice.types.TelecomManagerExtension.registerPhoneAccount
import com.twilio.twilio_voice.types.ValueBundleChanged
import com.twilio.voice.*
import com.twilio.voice.Call

class TVConnectionService : ConnectionService() {

    companion object {
        val TAG = "TwilioVoiceConnectionService"

        val SERVICE_TYPE_MICROPHONE: Int = 100

        //region ACTIONS_* Constants
        /**
         * Action used with [FirebaseMessagingService] to notify of incoming calls
         */
        const val ACTION_CALL_INVITE: String = "ACTION_CALL_INVITE"

        //region ACTIONS_* Constants
        /**
         * Action used with [EXTRA_CALL_HANDLE] to cancel a call connection.
         */
        const val ACTION_CANCEL_CALL_INVITE: String = "ACTION_CANCEL_CALL_INVITE"

        /**
         * Action used with [EXTRA_DIGITS] to send digits to the [TVConnection] active call.
         */
        const val ACTION_SEND_DIGITS: String = "ACTION_SEND_DIGITS"

        /**
         * Action used to hangup an active call connection.
         */
        const val ACTION_HANGUP: String = "ACTION_HANGUP"

        /**
         * Action used to toggle the speakerphone state of an active call connection.
         */
        const val ACTION_TOGGLE_SPEAKER: String = "ACTION_TOGGLE_SPEAKER"

        /**
         * Action used to toggle bluetooth state of an active call connection.
         */
        const val ACTION_TOGGLE_BLUETOOTH: String = "ACTION_TOGGLE_BLUETOOTH"

        /**
         * Action used to toggle hold state of an active call connection.
         */
        const val ACTION_TOGGLE_HOLD: String = "ACTION_TOGGLE_HOLD"

        /**
         * Action used to toggle mute state of an active call connection.
         */
        const val ACTION_TOGGLE_MUTE: String = "ACTION_TOGGLE_MUTE"

        /**
         * Action used to answer an incoming call connection.
         */
        const val ACTION_ANSWER: String = "ACTION_ANSWER"

        /**
         * Action used to answer an incoming call connection.
         */
        const val ACTION_INCOMING_CALL: String = "ACTION_INCOMING_CALL"

        /**
         * Action used to place an outgoing call connection.
         * Additional parameters are required: [EXTRA_TOKEN], [EXTRA_TO] and [EXTRA_FROM]. Optionally, [EXTRA_OUTGOING_PARAMS] for bundled extra custom parameters.
         */
        const val ACTION_PLACE_OUTGOING_CALL: String = "ACTION_PLACE_OUTGOING_CALL"
        //endregion

        //region EXTRA_* Constants
        /**
         * Extra used with [ACTION_SEND_DIGITS] to send digits to the [TVConnection] active call.
         */
        const val EXTRA_DIGITS: String = "EXTRA_DIGITS"

        /**
         * Extra used with [ACTION_CANCEL_CALL_INVITE] to cancel a call connection.
         */
        const val EXTRA_INCOMING_CALL_INVITE: String = "EXTRA_INCOMING_CALL_INVITE"

        /**
         * Extra used to identify a call connection.
         */
        const val EXTRA_CALL_HANDLE: String = "EXTRA_CALL_HANDLE"

        /**
         * Extra used with [ACTION_CANCEL_CALL_INVITE] to cancel a call connection
         */
        const val EXTRA_CANCEL_CALL_INVITE: String = "EXTRA_CANCEL_CALL_INVITE"

        /**
         * Extra used with [ACTION_PLACE_OUTGOING_CALL] to place an outgoing call connection. Denotes the Twilio Voice access token.
         */
        const val EXTRA_TOKEN: String = "EXTRA_TOKEN"

        /**
         * Extra used with [ACTION_PLACE_OUTGOING_CALL] to place an outgoing call connection. Denotes the recipient's identity.
         */
        const val EXTRA_TO: String = "EXTRA_TO"

        /**
         * Extra used with [ACTION_PLACE_OUTGOING_CALL] to place an outgoing call connection. Denotes the caller's identity.
         */
        const val EXTRA_FROM: String = "EXTRA_FROM"

        /**
         * Extra used with [ACTION_PLACE_OUTGOING_CALL] to send additional parameters to the [TVConnectionService] active call.
         */
        const val EXTRA_OUTGOING_PARAMS: String = "EXTRA_OUTGOING_PARAMS"

        /**
         * Extra used with [ACTION_TOGGLE_SPEAKER] to send additional parameters to the [TVCallConnection] active call.
         */
        const val EXTRA_SPEAKER_STATE: String = "EXTRA_SPEAKER_STATE"

        /**
         * Extra used with [ACTION_TOGGLE_BLUETOOTH] to send additional parameters to the [TVCallConnection] active call.
         */
        const val EXTRA_BLUETOOTH_STATE: String = "EXTRA_BLUETOOTH_STATE"

        /**
         * Extra used with [ACTION_TOGGLE_HOLD] to send additional parameters to the [TVCallConnection] active call.
         */
        const val EXTRA_HOLD_STATE: String = "EXTRA_HOLD_STATE"

        /**
         * Extra used with [ACTION_TOGGLE_MUTE] to send additional parameters to the [TVCallConnection] active call.
         */
        const val EXTRA_MUTE_STATE: String = "EXTRA_MUTE_STATE"
        //endregion

        fun getPhoneAccountHandle(ctx: Context): PhoneAccountHandle {
            val appName = ctx.appName
            val componentName = ComponentName(ctx, TVConnectionService::class.java)
//            val userHandle = UserHandle.getUserHandleForUid(0)
            return PhoneAccountHandle(componentName, appName)
        }
    }

    private val activeConnections = HashMap<String, TVCallConnection>()

    private fun getConnection(callSid: String): TVCallConnection? {
        return this.activeConnections[callSid]
    }

    private fun stopSelfSafe(): Boolean {
        if (!hasActiveCalls()) {
            stopSelf()
            return true
        } else {
            return false
        }
    }

    private fun hasActiveCalls(): Boolean {
        return this.activeConnections.isNotEmpty()
    }

    /**
     * Get the first active call handle, if any. Returns null if there are no active calls. If there are more than one active calls, the first call handle is returned.
     * Note: this might not necessarily correspond to the current active call.
     */
    private fun getActiveCallHandle(): String? {
        if (!hasActiveCalls()) return null
        return activeConnections.entries.firstOrNull { it.value.state == Connection.STATE_ACTIVE }?.key
    }

    private fun getIncomingCallHandle(): String? {
        if (!hasActiveCalls()) return null
        return activeConnections.entries.firstOrNull { it.value.state == Connection.STATE_RINGING }?.key
    }

    //region Service onStartCommand
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Thread.currentThread().contextClassLoader = CallInvite::class.java.classLoader
        super.onStartCommand(intent, flags, startId)
        intent?.let {
            when (it.action) {
                ACTION_SEND_DIGITS -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_SEND_DIGITS is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }
                    val digits = it.getStringExtra(EXTRA_DIGITS) ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_SEND_DIGITS is missing String EXTRA_DIGITS")
                        return@let
                    }

                    getConnection(callHandle)?.sendDigits(digits) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_SEND_DIGITS] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_CANCEL_CALL_INVITE -> {
                    // Load CancelledCallInvite class loader
                    // See: https://github.com/twilio/voice-quickstart-android/issues/561#issuecomment-1678613170
                    it.setExtrasClassLoader(CallInvite::class.java.classLoader)
                    val cancelledCallInvite = it.getParcelableExtraSafe<CancelledCallInvite>(EXTRA_CANCEL_CALL_INVITE) ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_CANCEL_CALL_INVITE is missing parcelable EXTRA_CANCEL_CALL_INVITE")
                        return@let
                    }

                    val callHandle = cancelledCallInvite.callSid
                    getConnection(callHandle)?.onAbort() ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_CANCEL_CALL_INVITE] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_INCOMING_CALL -> {
                    // Get PhoneAccountHandle
                    val phoneAccountHandle = getPhoneAccountHandle(applicationContext)

                    // Load CallInvite class loader & get callInvite
                    val callInvite = it.getParcelableExtraSafe<CallInvite>(EXTRA_INCOMING_CALL_INVITE) ?: run {
                        Log.e(TAG, "onStartCommand: 'ACTION_INCOMING_CALL' is missing parcelable 'EXTRA_INCOMING_CALL_INVITE'")
                        return@let
                    }

                    val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                    if (!telecomManager.hasReadPhonePermission(applicationContext)) {
                        Log.e(TAG, "onCallInvite: Permission to read phone state not granted or requested.")
                        callInvite.reject(applicationContext)
                        return@let
                    }

                    // Get telecom manager
                    if (!telecomManager.hasCallCapableAccount(applicationContext, phoneAccountHandle.componentName.className)) {
                        Log.e(
                            TAG, "onCallInvite: No registered phone account for PhoneHandle $phoneAccountHandle.\n" +
                                    "Check the following:\n" +
                                    "- Have you requested READ_PHONE_STATE permissions\n" +
                                    "- Have you registered a PhoneAccount \n" +
                                    "- Have you activated the Calling Account?"
                        )
                        callInvite.reject(applicationContext)
                        return@let
                    }

                    val myBundle: Bundle = Bundle().apply {
                        putParcelable(EXTRA_INCOMING_CALL_INVITE, callInvite)
                    }
                    myBundle.classLoader = CallInvite::class.java.classLoader

                    // Add extras for [addNewIncomingCall] method
                    val extras = Bundle().apply {
                        putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, myBundle)
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)

                        if (callInvite.customParameters.containsKey("_TWI_SUBJECT")) {
                            putString(TelecomManager.EXTRA_CALL_SUBJECT, callInvite.customParameters["_TWI_SUBJECT"])
                        }
                    }

                    // Add new incoming call to the telecom manager
                    telecomManager.addNewIncomingCall(phoneAccountHandle, extras)
                }

                ACTION_ANSWER -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getIncomingCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_HANGUP is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }

                    val connection = getConnection(callHandle) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_HANGUP] could not find connection for callHandle: $callHandle")
                        return@let
                    }

                    if(connection is TVCallInviteConnection) {
                        connection.acceptInvite()
                    } else {
                        Log.e(TAG, "onStartCommand: [ACTION_ANSWER] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_HANGUP -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_HANGUP is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }

                    getConnection(callHandle)?.disconnect() ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_HANGUP] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_PLACE_OUTGOING_CALL -> {
                    // check required EXTRA_TOKEN, EXTRA_TO, EXTRA_FROM
                    val token = it.getStringExtra(EXTRA_TOKEN) ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_PLACE_OUTGOING_CALL is missing String EXTRA_TOKEN")
                        return@let
                    }
                    val to = it.getStringExtra(EXTRA_TO) ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_PLACE_OUTGOING_CALL is missing String EXTRA_TO")
                        return@let
                    }
                    val from = it.getStringExtra(EXTRA_FROM) ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_PLACE_OUTGOING_CALL is missing String EXTRA_FROM")
                        return@let
                    }

                    // Get all params from bundle
                    val params = HashMap<String, String>()
                    val outGoingParams = it.getParcelableExtraSafe<Bundle>(EXTRA_OUTGOING_PARAMS)
                    outGoingParams?.keySet()?.forEach { key ->
                        outGoingParams.getString(key)?.let { value ->
                            params[key] = value
                        }
                    }

                    // Add required params
                    params[EXTRA_FROM] = from
                    params[EXTRA_TO] = to
                    params[EXTRA_TOKEN] = token

                    // Create Twilio Param bundles
                    val myBundle = Bundle().apply {
                        putBundle(EXTRA_OUTGOING_PARAMS, Bundle().apply {
                            params.forEach { (key, value) ->
                                putString(key, value)
                            }
                        })
                    }

                    // Get PhoneAccountHandle
                    val phoneAccountHandle = getPhoneAccountHandle(applicationContext)

                    // Get telecom manager
                    val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                    if (!telecomManager.hasReadPhonePermission(applicationContext)) {
                        Log.e(TAG, "onStartCommand: Missing READ_PHONE_STATE permission")
                        return@let
                    }
                    if (!telecomManager.hasCallCapableAccount(applicationContext, phoneAccountHandle.componentName.className)) {
                        Log.e(TAG, "onStartCommand: No registered phone account for PhoneHandle $phoneAccountHandle")
                        telecomManager.registerPhoneAccount(applicationContext, phoneAccountHandle, applicationContext.appName)
                    }

                    // Create outgoing extras
                    val extras = Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                        putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, myBundle)
                    }

                    val address: Uri = Uri.fromParts(PhoneAccount.SCHEME_TEL, to, null)
                    telecomManager.placeCall(address, extras)
                }

                ACTION_TOGGLE_BLUETOOTH -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_TOGGLE_BLUETOOTH is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }
                    val bluetoothState = it.getBooleanExtra(EXTRA_BLUETOOTH_STATE, false)

                    getConnection(callHandle)?.toggleBluetooth(bluetoothState) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_TOGGLE_BLUETOOTH] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_TOGGLE_HOLD -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_TOGGLE_HOLD is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }
                    val holdState = it.getBooleanExtra(EXTRA_HOLD_STATE, false)

                    getConnection(callHandle)?.toggleHold(holdState) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_TOGGLE_HOLD] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_TOGGLE_MUTE -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_TOGGLE_MUTE is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }
                    val muteState = it.getBooleanExtra(EXTRA_MUTE_STATE, false)

                    getConnection(callHandle)?.toggleMute(muteState) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_TOGGLE_MUTE] could not find connection for callHandle: $callHandle")
                    }
                }

                ACTION_TOGGLE_SPEAKER -> {
                    val callHandle = it.getStringExtra(EXTRA_CALL_HANDLE) ?: getActiveCallHandle() ?: run {
                        Log.e(TAG, "onStartCommand: ACTION_TOGGLE_SPEAKER is missing String EXTRA_CALL_HANDLE")
                        return@let
                    }
                    val speakerState = it.getBooleanExtra(EXTRA_SPEAKER_STATE, false)
                    getConnection(callHandle)?.toggleSpeaker(speakerState) ?: run {
                        Log.e(TAG, "onStartCommand: [ACTION_TOGGLE_SPEAKER] could not find connection for callHandle: $callHandle")
                    }
                }

                else -> {
                    Log.e(TAG, "onStartCommand: unknown action: ${it.action}")
                }
            }
        } ?: run {
            Log.e(TAG, "onStartCommand: intent is null")
        }
        return START_STICKY
    }
    //endregion

    override fun onCreateIncomingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        super.onCreateIncomingConnection(connectionManagerPhoneAccount, request)
        Log.d(TAG, "onCreateIncomingConnection")

        val extras = request?.extras
        val myBundle: Bundle = extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS) ?: run {
            Log.e(TAG, "onCreateIncomingConnection: request is missing Bundle EXTRA_INCOMING_CALL_EXTRAS")
            return super.onCreateIncomingConnection(connectionManagerPhoneAccount, request)
        }

        myBundle.classLoader = CallInvite::class.java.classLoader
        val ci: CallInvite = myBundle.getParcelableSafe(EXTRA_INCOMING_CALL_INVITE) ?: run {
            Log.e(TAG, "onCreateIncomingConnection: request is missing CallInvite EXTRA_INCOMING_CALL_INVITE")
            return super.onCreateIncomingConnection(connectionManagerPhoneAccount, request)
        }

        val address = ci.from?.let { it1 -> Uri.parse("tel:${it1}") }
        val callSid: String = ci.callSid;
        val onAction: ValueBundleChanged<String> = ValueBundleChanged { event: String?, extra: Bundle? ->
            sendBroadcastEvent(applicationContext, event ?: "", callSid, extra)
        }
        val onEvent: ValueBundleChanged<String> = ValueBundleChanged { event: String?, extra: Bundle? ->
            sendBroadcastEvent(applicationContext, event ?: "", callSid, extra)
        }
        val onDisconnect: CompletionHandler<DisconnectCause> = CompletionHandler {
            if (activeConnections.containsKey(callSid)) {
                activeConnections.remove(callSid)
            }
            stopForegroundService()
            stopSelfSafe()
        }
        val connection = TVCallInviteConnection(applicationContext, ci, onEvent, onAction, onDisconnect)
        val newBundle = request.extras.also { it ->
            it.remove(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)
        }
//        ci.customParameters["_TWI_SUBJECT"]?.let {
//            connection.extras.putString(TelecomManager.EXTRA_CALL_SUBJECT, it)
//        }
        connection.extras = newBundle

        val storage: Storage = StorageImpl(applicationContext)
        val callParams: TVCallParameters = TVCallParametersImpl(ci, storage);
        connection.setCallerDisplayName(callParams.from, TelecomManager.PRESENTATION_ALLOWED)
//        connection.setAddress(address ?: Uri.parse("tel:Unknown Caller"), TelecomManager.PRESENTATION_ALLOWED)
        connection.setRinging()

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            val filename = ci.from ?: "caller"
//            val uri = ImageUtils.saveToCache(applicationContext, "https://avatars.githubusercontent.com/u/29998700?v=4", filename)
//            val bundle = Bundle().apply {
//                putParcelable(TelecomManager.EXTRA_PICTURE_URI, uri)
//            }
//            connection.putExtras(bundle)
//        }

        // this is the name of the caller that will be displayed on the Android UI, preference is given to the name of the contact set by setAddress
//         connection.setCallerDisplayName(ci.customParameters["_TWI_SUBJECT"], TelecomManager.PRESENTATION_ALLOWED)
        activeConnections[ci.callSid] = connection
        startForegroundService()
        return connection
    }

    override fun onCreateOutgoingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        super.onCreateOutgoingConnection(connectionManagerPhoneAccount, request)
        Log.d(TAG, "onCreateOutgoingConnection")

        val extras = request?.extras
        val myBundle: Bundle = extras?.getBundle(EXTRA_OUTGOING_PARAMS) ?: run {
            Log.e(TAG, "onCreateOutgoingConnection: request is missing Bundle EXTRA_OUTGOING_PARAMS")
            return super.onCreateOutgoingConnection(connectionManagerPhoneAccount, request)
        }

        // check required EXTRA_TOKEN, EXTRA_TO, EXTRA_FROM
        val token: String = myBundle.getString(EXTRA_TOKEN) ?: run {
            Log.e(TAG, "onCreateOutgoingConnection: ACTION_PLACE_OUTGOING_CALL is missing String EXTRA_TOKEN")
            return super.onCreateOutgoingConnection(connectionManagerPhoneAccount, request)
        }
        val to = myBundle.getString(EXTRA_TO) ?: run {
            Log.e(TAG, "onCreateOutgoingConnection: ACTION_PLACE_OUTGOING_CALL is missing String EXTRA_TO")
            return super.onCreateOutgoingConnection(connectionManagerPhoneAccount, request)
        }
        val from = myBundle.getString(EXTRA_FROM) ?: run {
            Log.e(TAG, "onCreateOutgoingConnection: ACTION_PLACE_OUTGOING_CALL is missing String EXTRA_FROM")
            return super.onCreateOutgoingConnection(connectionManagerPhoneAccount, request)
        }

        // Get all params from bundle
        val params = HashMap<String, String>()
        myBundle.keySet().forEach { key ->
            when (key) {
                EXTRA_TO, EXTRA_FROM, EXTRA_TOKEN -> {}
                else -> {
                    myBundle.getString(key)?.let { value ->
                        params[key] = value
                    }
                }
            }
        }
        params["From"] = from
        params["To"] = to

        // create connect options
        val connectOptions = ConnectOptions.Builder(token)
            .params(params)
            .build()

        // create outgoing connection
        val connection = TVCallConnection(applicationContext)
        val call = Voice.connect(applicationContext, connectOptions, connection)
        val onCallStateListener: CompletionHandler<Call.State> = CompletionHandler { state ->
            if (state == Call.State.RINGING || state == Call.State.CONNECTED) {
                val callSid = call.sid!!
                activeConnections[callSid] = connection
            }
        }
        connection.setOnCallStateListener(onCallStateListener)
        connection.setCall(call)

        val onAction: ValueBundleChanged<String> = ValueBundleChanged { event: String?, extra: Bundle? ->
            sendBroadcastEvent(applicationContext, event ?: "", call.sid, extra)
        }
        val onEvent: ValueBundleChanged<String> = ValueBundleChanged { event: String?, extra: Bundle? ->
            sendBroadcastEvent(applicationContext, event ?: "", call.sid, extra)
        }
        val onDisconnect: CompletionHandler<DisconnectCause> = CompletionHandler {
            if (activeConnections.containsKey(call.sid)) {
                activeConnections.remove(call.sid)
            }
            stopForegroundService()
            stopSelfSafe()
        }
        connection.setOnCallEventListener(onEvent)
        connection.setOnCallEventListener(onEvent)
        connection.setOnCallActionListener(onAction)
        connection.setOnCallDisconnected(onDisconnect)

        connection.extras = request.extras

        val mStorage: Storage = StorageImpl(applicationContext)
        val caller: String = mStorage.getRegisteredClient(to) ?: mStorage.defaultCaller
        connection.setCallerDisplayName(caller, TelecomManager.PRESENTATION_ALLOWED)

        // create a contact with the URL or custom URI with scheme twi://
//        connection.setAddress(Uri.parse("tel:${to}"), TelecomManager.PRESENTATION_ALLOWED)
        connection.setInitialized()

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            val filename = params[Constants.FROM] ?: "caller"
//            val uri = ImageUtils.saveToCache(applicationContext, "https://avatars.githubusercontent.com/u/29998700?v=4", filename)
//            val bundle = Bundle().apply {
//                putParcelable(TelecomManager.EXTRA_PICTURE_URI, uri)
//            }
//            connection.putExtras(bundle)
//        }
        startForegroundService()

        // this is the name of the caller that will be displayed on the Android UI, preference is given to the name of the contact set by setAddress
        return connection
    }

    private fun sendBroadcastEvent(ctx: Context, event: String, callSid: String?, extras: Bundle? = null) {
        Intent(ctx, TVBroadcastReceiver::class.java).apply {
            action = event
            putExtra(EXTRA_CALL_HANDLE, callSid)
            extras?.let { putExtras(it) }
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(this)
        }
    }

    override fun onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?) {
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request)
        Log.d(TAG, "onCreateOutgoingConnectionFailed")
        stopForegroundService()
    }

    override fun onCreateIncomingConnectionFailed(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?) {
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
        Log.d(TAG, "onCreateIncomingConnectionFailed")
        stopForegroundService()
    }

    private fun getOrCreateChannel(): NotificationChannel {
        val id = "${applicationContext.packageName}_calls"
        val name = applicationContext.appName
        val descriptionText = "Active Voice Calls"
        val importance = NotificationManager.IMPORTANCE_NONE
        val channel = NotificationChannel(id, name, importance).apply {
            description = descriptionText
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        return channel
    }

    private fun createNotification(): Notification {
        val channel = getOrCreateChannel()

        val intent = Intent(applicationContext, TVConnectionService::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent: PendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, flag);

        return Notification.Builder(this, channel.id).apply {
            setOngoing(true)
            setContentTitle("Voice Calls")
            setCategory(Notification.CATEGORY_SERVICE)
            setContentIntent(pendingIntent)
            setSmallIcon(R.drawable.ic_microphone)
        }.build()
    }

    private fun cancelNotification() {
        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(SERVICE_TYPE_MICROPHONE)
    }

    /// Source: https://github.com/react-native-webrtc/react-native-callkeep/blob/master/android/src/main/java/io/wazo/callkeep/VoiceConnectionService.java#L295
    private fun startForegroundService() {
        val notification = createNotification()
        Log.d(TAG, "[VoiceConnectionService] Starting foreground service")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Optional for Android +11, required for Android +14
                startForeground(SERVICE_TYPE_MICROPHONE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(SERVICE_TYPE_MICROPHONE, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[VoiceConnectionService] Can't start foreground service : $e")
        }
    }

    /// Source: https://github.com/react-native-webrtc/react-native-callkeep/blob/master/android/src/main/java/io/wazo/callkeep/VoiceConnectionService.java#L352C5-L377C6
    private fun stopForegroundService() {
        Log.d(TAG, "[VoiceConnectionService] stopForegroundService")
        try {
            stopForeground(SERVICE_TYPE_MICROPHONE)
            cancelNotification()
        } catch (e: java.lang.Exception) {
            Log.w(TAG, "[VoiceConnectionService] can't stop foreground service :$e")
        }
    }
}