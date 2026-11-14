#!/usr/bin/env python3
"""Apply the safe Xiaomi F5 -> Aerial Voice bridge to upstream Aerial v1.95.

Run from a checkout of pengchujin/aerial-tv tag v1.95:
  python3 patch/patch_aerial_voice.py <aerial-v1.95-source-root>

The patch consumes only F5 from the known Xiaomi remote. It does not alter
HomeGuardService.updateKeyFilter() or any mouse/gamepad safety conditions.
"""
from pathlib import Path
import sys

MARKER = "// AERIAL_VOICE_BRIDGE_V2"

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 patch_aerial_voice.py <aerial-v1.95-source-root>")

root = Path(sys.argv[1]).resolve()
service = root / "app/src/main/java/app/orchard/tv/guard/HomeGuardService.kt"
manifest = root / "app/src/main/AndroidManifest.xml"
if not service.is_file() or not manifest.is_file():
    raise SystemExit("Not an Aerial source root: expected HomeGuardService.kt and AndroidManifest.xml")

src = service.read_text(encoding="utf-8")
if MARKER in src:
    print("Aerial Voice bridge v2 is already applied; no changes made.")
    raise SystemExit(0)
if "// AERIAL_VOICE_BRIDGE_V1" in src:
    raise SystemExit("An older Aerial Voice bridge v1 is present. Re-clone clean upstream Aerial v1.95 before applying v2.")

handler_anchor = "    private val handler = Handler(Looper.getMainLooper())\n"
if src.count(handler_anchor) != 1:
    raise SystemExit("Unexpected HomeGuardService.kt: cannot uniquely locate main Handler.")

fields = '''
    // AERIAL_VOICE_BRIDGE_V2: the Xiaomi F5 remote button is forwarded as press/release intents.
    private var aerialVoiceF5DownAt = 0L
    private var aerialVoiceF5LongTriggered = false
    private val aerialVoiceF5LongPress = Runnable {
        if (aerialVoiceF5DownAt == 0L) return@Runnable
        aerialVoiceF5LongTriggered = true
        openAerialVoice(startListening = false, stopListening = false, continuous = true)
    }
'''
src = src.replace(handler_anchor, handler_anchor + fields, 1)

key_anchor = '''    override fun onKeyEvent(event: KeyEvent): Boolean {
        keySeen = true
        if (!GuardSetup.isUserUnlocked(this)) return false
'''
if src.count(key_anchor) != 1:
    raise SystemExit("Unexpected HomeGuardService.kt: cannot uniquely locate onKeyEvent entry.")

bridge = '''    // AERIAL_VOICE_BRIDGE_V2: this only runs when Aerial's own key-filter policy delivers events.
    private fun isAerialVoiceRemote(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        return device.vendorId == 0x2717 && device.productId == 0x32B8
    }

    private fun handleAerialVoiceKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                handler.removeCallbacks(aerialVoiceF5LongPress)
                aerialVoiceF5DownAt = SystemClock.uptimeMillis()
                aerialVoiceF5LongTriggered = false
                openAerialVoice(startListening = true, stopListening = false, continuous = false)
                handler.postDelayed(aerialVoiceF5LongPress, 650L)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(aerialVoiceF5LongPress)
            val wasLongPress = aerialVoiceF5LongTriggered
            aerialVoiceF5DownAt = 0L
            openAerialVoice(startListening = false, stopListening = true, continuous = wasLongPress)
            aerialVoiceF5LongTriggered = false
            return true
        }
        return true
    }

    private fun openAerialVoice(startListening: Boolean, stopListening: Boolean, continuous: Boolean) {
        val candidates = listOf("app.orchard.tv.aerialvoice.mictest06", "app.orchard.tv.aerialvoice.mictest", "app.orchard.tv.aerialvoice")
        val targetPackage = candidates.firstOrNull { pkg ->
            runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
        if (targetPackage == null) {
            Log.w("AerialVoiceBridge", "Aerial Voice is not installed.")
            DiagLog.i("voice bridge: Aerial Voice package not installed")
            return
        }
        val intent = Intent()
            .setClassName(targetPackage, "app.orchard.tv.aerialvoice.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("app.orchard.tv.aerialvoice.START_LISTENING", startListening)
            .putExtra("app.orchard.tv.aerialvoice.STOP_LISTENING", stopListening)
            .putExtra("app.orchard.tv.aerialvoice.SET_CONTINUOUS", true)
            .putExtra("app.orchard.tv.aerialvoice.CONTINUOUS_MODE", continuous)
            .putExtra("app.orchard.tv.aerialvoice.TRIGGER_SOURCE", "xiaomi_remote_f5")
        runCatching { startActivity(intent) }
            .onSuccess {
                Log.i("AerialVoiceBridge", "Xiaomi F5 forwarded (start=$startListening, stop=$stopListening, continuous=$continuous)")
                DiagLog.i("voice bridge: F5 start=$startListening stop=$stopListening continuous=$continuous")
            }
            .onFailure { Log.w("AerialVoiceBridge", "Could not open/notify Aerial Voice.", it) }
    }

'''
src = src.replace(key_anchor, bridge + key_anchor, 1)

unlocked_anchor = '''        if (!GuardSetup.isUserUnlocked(this)) return false
'''
if src.count(unlocked_anchor) != 1:
    raise SystemExit("Unexpected HomeGuardService.kt: cannot uniquely locate unlocked-user guard.")
src = src.replace(unlocked_anchor, unlocked_anchor + '''        if (event.keyCode == KeyEvent.KEYCODE_F5 && isAerialVoiceRemote(event)) {
            return handleAerialVoiceKey(event)
        }
''', 1)
service.write_text(src, encoding="utf-8")

xml = manifest.read_text(encoding="utf-8")
queries_anchor = "    <queries>\n"
if xml.count(queries_anchor) != 1:
    raise SystemExit("Unexpected AndroidManifest.xml: cannot uniquely locate <queries>.")
for package in ("app.orchard.tv.aerialvoice.mictest06", "app.orchard.tv.aerialvoice.mictest", "app.orchard.tv.aerialvoice"):
    query = f'        <package android:name="{package}" />\n'
    if query not in xml:
        xml = xml.replace(queries_anchor, queries_anchor + query, 1)
manifest.write_text(xml, encoding="utf-8")

print("Aerial Voice bridge v2 applied.")
print("F5 DOWN starts capture; F5 UP submits the phrase; holding >=650 ms enables hands-free mode.")
print("No changes were made to updateKeyFilter(), mouse safety, or gamepad safety.")
print("Important: the bridge works only when HomeGuard already receives key events under its existing policy.")
