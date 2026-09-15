package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.model.AppCommand
import com.example.model.PrimeContact
import com.example.service.AccessibilityHelperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS_NAME = "myra_prefs"
        private const val KEY_PRIME_JSON = "prime_contacts_json"
        private const val KEY_LEGACY_NAME = "prime_name"
        private const val KEY_LEGACY_NUM = "prime_number"

        private val APP_PACKAGE_MAP = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "telegram" to "org.telegram.messenger",
            "snapchat" to "com.snapchat.android",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "phone" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts",
            "play store" to "com.android.vending",
            "amazon" to "in.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "zoom" to "us.zoom.videomeetings",
            "meet" to "com.google.android.apps.meetings",
            "teams" to "com.microsoft.teams",
            "tiktok" to "com.zhiliaoapp.musically",
            "discord" to "com.discord",
            "linkedin" to "com.linkedin.android"
        )
    }

    private val _commandResult = MutableLiveData<String?>()
    val commandResult: LiveData<String?> = _commandResult

    private val _primeContacts = MutableLiveData<MutableList<PrimeContact>>()
    val primeContacts: LiveData<MutableList<PrimeContact>> = _primeContacts

    private var isFlashlightOn = false

    init {
        loadPrimeContacts()
    }

    fun executeCommand(command: AppCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            when (command.type) {
                AppCommand.OPEN_APP -> {
                    val appName = command.params["app_name"] ?: ""
                    openApp(appName)
                }
                AppCommand.CLOSE_APP -> {
                    closeApp()
                }
                AppCommand.CALL -> {
                    val target = command.params["name"] ?: ""
                    callContact(target)
                }
                AppCommand.SMS -> {
                    val target = command.params["name"] ?: ""
                    val msg = command.params["message"] ?: "Hello"
                    sendSms(target, msg)
                }
                AppCommand.WHATSAPP_MSG -> {
                    val target = command.params["name"] ?: ""
                    val msg = command.params["message"] ?: "Hello"
                    sendWhatsApp(target, msg)
                }
                AppCommand.PRIME_CALL -> {
                    val index = command.params["index"]?.toIntOrNull() ?: 0
                    callPrimeContact(index)
                }
                AppCommand.PRIME_MSG -> {
                    val index = command.params["index"]?.toIntOrNull() ?: 0
                    msgPrimeContact(index)
                }
                AppCommand.VOLUME_UP -> adjustVolume(true)
                AppCommand.VOLUME_DOWN -> adjustVolume(false)
                AppCommand.FLASHLIGHT_ON -> toggleFlashlight(true)
                AppCommand.FLASHLIGHT_OFF -> toggleFlashlight(false)
                else -> {
                    Log.d(TAG, "Unhandled command: ${command.type}")
                }
            }
        }
    }

    private fun openApp(appName: String) {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val cleanName = appName.lowercase().trim()

        var packageName = APP_PACKAGE_MAP[cleanName]
        if (packageName == null) {
            for ((key, pkg) in APP_PACKAGE_MAP) {
                if (cleanName.contains(key) || key.contains(cleanName)) {
                    packageName = pkg
                    break
                }
            }
        }

        if (packageName == null) {
            // Search all installed packages
            try {
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (app in apps) {
                    val label = pm.getApplicationLabel(app).toString().lowercase()
                    if (label.contains(cleanName) || cleanName.contains(label)) {
                        packageName = app.packageName
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error listing installed apps: ${e.message}")
            }
        }

        if (packageName != null) {
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                _commandResult.postValue("Haan! $appName khol diya hai.")
                return
            }
        }

        _commandResult.postValue("Maaf karna, $appName install nahi mila phone me.")
    }

    private fun closeApp() {
        val service = AccessibilityHelperService.instance
        if (service != null) {
            service.closeCurrentApp()
            _commandResult.postValue("App band kar diya aur home screen aa gaya.")
        } else {
            _commandResult.postValue("Accessibility permission chalu karni hogi app close karne ke liye.")
        }
    }

    private fun callContact(nameOrNumber: String) {
        val context = getApplication<Application>()
        val resolvedNumber = resolvePhoneNumber(nameOrNumber)

        if (resolvedNumber.isNotEmpty()) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$resolvedNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                _commandResult.postValue("Bilkul! $nameOrNumber ko call lagaya ja raha hai.")
            } else {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$resolvedNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                _commandResult.postValue("$nameOrNumber ka number dialer me open kiya.")
            }
        } else {
            _commandResult.postValue("Contacts me $nameOrNumber nahi mila.")
        }
    }

    private fun sendSms(nameOrNumber: String, message: String) {
        val context = getApplication<Application>()
        val number = resolvePhoneNumber(nameOrNumber)
        val target = if (number.isNotEmpty()) number else nameOrNumber

        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("smsto:$target")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            _commandResult.postValue("SMS ready kar diya hai.")
        } catch (e: Exception) {
            _commandResult.postValue("SMS open karne me problem aayi.")
        }
    }

    private fun sendWhatsApp(nameOrNumber: String, message: String) {
        val context = getApplication<Application>()
        val number = resolvePhoneNumber(nameOrNumber)
        val cleanNumber = number.replace("+", "").replace(" ", "").replace("-", "")

        val uri = if (cleanNumber.isNotEmpty()) {
            Uri.parse("https://wa.me/$cleanNumber?text=${Uri.encode(message)}")
        } else {
            Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.whatsapp")
        }
        try {
            context.startActivity(intent)
            _commandResult.postValue("WhatsApp open kar diya hai.")
        } catch (e: Exception) {
            _commandResult.postValue("WhatsApp open nahi ho paya.")
        }
    }

    private fun callPrimeContact(index: Int) {
        val contacts = _primeContacts.value ?: emptyList()
        if (contacts.isNotEmpty() && index < contacts.size) {
            val contact = contacts[index]
            callContact(contact.number)
        } else {
            _commandResult.postValue("Prime contact saved nahi hai. Settings me jaakar add karo.")
        }
    }

    private fun msgPrimeContact(index: Int) {
        val contacts = _primeContacts.value ?: emptyList()
        if (contacts.isNotEmpty() && index < contacts.size) {
            val contact = contacts[index]
            sendWhatsApp(contact.number, "Hi")
        } else {
            _commandResult.postValue("Prime contact saved nahi hai. Settings me add karo.")
        }
    }

    private fun toggleFlashlight(on: Boolean) {
        val context = getApplication<Application>()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, on)
                isFlashlightOn = on
                _commandResult.postValue(if (on) "Torch on ho gaya." else "Torch band kar diya.")
            }
        } catch (e: Exception) {
            _commandResult.postValue("Torch control karne me error aaya.")
        }
    }

    private fun adjustVolume(raise: Boolean) {
        val context = getApplication<Application>()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        _commandResult.postValue(if (raise) "Volume badha diya." else "Volume kam kar diya.")
    }

    fun acceptCall() {
        val context = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                telecom?.acceptRingingCall()
                _commandResult.postValue("Call accept kar liya.")
                return
            }
        }
        _commandResult.postValue("Call answer karne ki permission nahi mili.")
    }

    fun rejectCall() {
        val context = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                telecom?.endCall()
                _commandResult.postValue("Call reject kar diya.")
                return
            }
        }
        _commandResult.postValue("Call reject kar diya.")
    }

    private fun resolvePhoneNumber(nameOrNumber: String): String {
        if (nameOrNumber.matches(Regex("""[+0-9\s\-]+"""))) {
            return nameOrNumber
        }

        val context = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ""
        }

        try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$nameOrNumber%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val num = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    return num
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact: ${e.message}")
        }
        return ""
    }

    // PRIME CONTACTS PERSISTENCE
    fun loadPrimeContacts() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = mutableListOf<PrimeContact>()
        val jsonString = prefs.getString(KEY_PRIME_JSON, null)

        if (jsonString != null) {
            try {
                val array = JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(PrimeContact(obj.getString("name"), obj.getString("number")))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing prime contacts JSON: ${e.message}")
            }
        } else {
            // Legacy migration check
            val legacyName = prefs.getString(KEY_LEGACY_NAME, null)
            val legacyNum = prefs.getString(KEY_LEGACY_NUM, null)
            if (!legacyName.isNullOrEmpty() && !legacyNum.isNullOrEmpty()) {
                list.add(PrimeContact(legacyName, legacyNum))
                savePrimeContacts(list)
            }
        }

        _primeContacts.postValue(list)
    }

    fun savePrimeContacts(contacts: List<PrimeContact>) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (c in contacts) {
            val obj = JSONObject().apply {
                put("name", c.name)
                put("number", c.number)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_PRIME_JSON, array.toString()).apply()
        _primeContacts.postValue(contacts.toMutableList())
    }

    fun addPrimeContact(name: String, number: String) {
        val current = _primeContacts.value ?: mutableListOf()
        current.add(PrimeContact(name, number))
        savePrimeContacts(current)
    }

    fun deletePrimeContact(index: Int) {
        val current = _primeContacts.value ?: mutableListOf()
        if (index in 0 until current.size) {
            current.removeAt(index)
            savePrimeContacts(current)
        }
    }
}
