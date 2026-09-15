package com.example.ai

import com.example.model.AppCommand

object CommandParser {

    fun parse(text: String): AppCommand? {
        val clean = text.trim().lowercase()
        if (clean.isEmpty()) return null

        // Prime contact patterns
        if (clean.contains("close friend") && (clean.contains("call") || clean.contains("karo"))) {
            return AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "0"))
        }
        if (clean.contains("second contact") && (clean.contains("call") || clean.contains("karo"))) {
            return AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "1"))
        }
        if ((clean.contains("meri jaan") || clean.contains("my love")) &&
            (clean.contains("msg") || clean.contains("message") || clean.contains("sms") || clean.contains("bhejo"))
        ) {
            return AppCommand(AppCommand.PRIME_MSG, mapOf("index" to "0"))
        }

        // Flashlight
        if ((clean.contains("torch") || clean.contains("flashlight")) &&
            (clean.contains("on") || clean.contains("jalao") || clean.contains("chalu") || clean.contains("start"))
        ) {
            return AppCommand(AppCommand.FLASHLIGHT_ON)
        }
        if ((clean.contains("torch") || clean.contains("flashlight")) &&
            (clean.contains("off") || clean.contains("bujhao") || clean.contains("band") || clean.contains("stop"))
        ) {
            return AppCommand(AppCommand.FLASHLIGHT_OFF)
        }

        // Volume
        if ((clean.contains("volume") || clean.contains("awaz") || clean.contains("awaaz")) &&
            (clean.contains("up") || clean.contains("badhao") || clean.contains("tez") || clean.contains("badao") || clean.contains("increase"))
        ) {
            return AppCommand(AppCommand.VOLUME_UP)
        }
        if ((clean.contains("volume") || clean.contains("awaz") || clean.contains("awaaz")) &&
            (clean.contains("down") || clean.contains("kam") || clean.contains("ghatao") || clean.contains("decrease") || clean.contains("low"))
        ) {
            return AppCommand(AppCommand.VOLUME_DOWN)
        }

        // WiFi
        if (clean.contains("wifi")) {
            if (clean.contains("on") || clean.contains("chalu") || clean.contains("start")) return AppCommand(AppCommand.WIFI_ON)
            if (clean.contains("off") || clean.contains("band") || clean.contains("stop")) return AppCommand(AppCommand.WIFI_OFF)
        }

        // Bluetooth
        if (clean.contains("bluetooth")) {
            if (clean.contains("on") || clean.contains("chalu") || clean.contains("start")) return AppCommand(AppCommand.BLUETOOTH_ON)
            if (clean.contains("off") || clean.contains("band") || clean.contains("stop")) return AppCommand(AppCommand.BLUETOOTH_OFF)
        }

        // Close app
        if (clean.contains("band karo") || clean.contains("close app") || clean.contains("back jao") || clean.contains("home screen")) {
            return AppCommand(AppCommand.CLOSE_APP)
        }

        // Open App
        // e.g. "youtube kholo", "open youtube", "instagram chalao", "open whatsapp"
        val openAppRegex = Regex("""(?:open|kholo|chalao|launch)\s+([a-zA-Z0-9\s]+)|([a-zA-Z0-9\s]+)\s+(?:kholo|chalao|open)""")
        val openMatch = openAppRegex.find(clean)
        if (openMatch != null) {
            val appCandidate = (openMatch.groupValues[1].ifEmpty { openMatch.groupValues[2] }).trim()
            if (appCandidate.isNotEmpty() && !isSystemActionKeyword(appCandidate)) {
                return AppCommand(AppCommand.OPEN_APP, mapOf("app_name" to appCandidate))
            }
        }

        // WhatsApp message
        // "whatsapp karo rahul ko hello", "send whatsapp to rahul"
        if (clean.contains("whatsapp")) {
            val waRegex = Regex("""whatsapp\s+(?:karo|message|msg|send)\s+([a-zA-Z0-9]+)""")
            val m = waRegex.find(clean)
            if (m != null) {
                val target = m.groupValues[1].trim()
                return AppCommand(AppCommand.WHATSAPP_MSG, mapOf("name" to target))
            }
        }

        // SMS
        // "sms bhejo rahul ko", "send sms to rahul"
        if (clean.contains("sms") || clean.contains("message bhejo")) {
            val smsRegex = Regex("""(?:sms|message)\s+(?:bhejo|send|to)\s+([a-zA-Z0-9]+)""")
            val m = smsRegex.find(clean)
            if (m != null) {
                val target = m.groupValues[1].trim()
                return AppCommand(AppCommand.SMS, mapOf("name" to target))
            }
        }

        // Call
        // "rahul ko call karo", "call rahul", "phone lagao rahul ko"
        val callRegex = Regex("""(?:call\s+(?:karo\s+)?([a-zA-Z0-9]+))|([a-zA-Z0-9]+)\s+ko\s+call\s+karo|(?:phone\s+lagao\s+([a-zA-Z0-9]+))""")
        val callMatch = callRegex.find(clean)
        if (callMatch != null) {
            val target = listOf(callMatch.groupValues[1], callMatch.groupValues[2], callMatch.groupValues[3])
                .firstOrNull { it.isNotEmpty() }?.trim()
            if (!target.isNullOrEmpty() && !isSystemActionKeyword(target)) {
                return AppCommand(AppCommand.CALL, mapOf("name" to target))
            }
        }

        return null
    }

    private fun isSystemActionKeyword(word: String): Boolean {
        return word in listOf("torch", "flashlight", "volume", "wifi", "bluetooth", "app", "phone", "call", "sms")
    }
}
