#!/usr/bin/env python3
"""Apply the minimal F5 -> Aerial Voice bridge to upstream Aerial v1.95.

Run from a checkout of pengchujin/aerial-tv tag v1.95:
  python3 patch/patch_aerial_voice.py .

The patch does not change Aerial's key-filter policy. It only handles F5 when
HomeGuardService is already receiving keys under Aerial's existing safety rules.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 patch_aerial_voice.py <aerial-v1.95-source-root>")

root = Path(sys.argv[1]).resolve()
service = root / "app/src/main/java/app/orchard/tv/guard/HomeGuardService.kt"
manifest = root / "app/src/main/AndroidManifest.xml"
if not service.is_file() or not manifest.is_file():
    raise SystemExit("This does not look like the Aerial source root: expected HomeGuardService.kt and AndroidManifest.xml")

src = service.read_text(encoding="utf-8")
marker = "// AERIAL_VOICE_BRIDGE_V1"
if marker in src:
    print("Aerial Voice bridge is already applied; no changes made.")
    raise SystemExit(0)

handler_anchor = "    private val handler = Handler(Looper.getMainLooper())\n"
if src.count(handler_anchor) != 1:
    raise SystemExit("Unexpected HomeGuardService.kt: could not uniquely locate main Handler declaration.")

fields = '''
    // AERIAL_VOICE_BRIDGE_V1: only the Xiaomi remote's F5 is consumed; all other keys keep Aerial's existing policy.
    private var aerialVoiceF5DownAt = 0L
    private var aerialVoiceF5LongTriggered = false
    private val aerialVoiceF5LongPress = Runnable {
        if (aerialVoiceF5DownAt == 0L) return@Runnable
        aerialVoiceF5DownAt = 0L
        aerialVoiceF5LongTriggered = true
        openAerialVoice(startContinuous = true)
    }
'''
src = src.replace(handler_anchor, handler_anchor + fields, 1)

key_anchor = '''    override fun onKeyEvent(event: KeyEvent): Boolean {
        keySeen = true
        if (!GuardSetup.isUserUnlocked(this)) return false
'''
if src.count(key_anchor) != 1:
    raise SystemExit("Unexpected HomeGuardService.kt: could not uniquely locate onKeyEvent entry point.")

key_handler = '''    // AERIAL_VOICE_BRIDGE_V1: the service only sees keys when Aerial's own updateKeyFilter permits it.
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
                handler.postDelayed(aerialVoiceF5LongPress, 650L)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(aerialVoiceF5LongPress)
            if (aerialVoiceF5DownAt != 0L) {
                aerialVoiceF5DownAt = 0L
                if (!event.isCanceled && !aerialVoiceF5LongTriggered) {
                    openAerialVoice(startContinuous = false)
                }
            }
            aerialVoiceF5LongTriggered = false
            return true
        }
        return true
    }

    private fun openAerialVoice(startContinuous: Boolean) {
        val intent = Intent()
            .setClassName("app.orchard.tv.aerialvoice", "app.orchard.tv.aerialvoice.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("app.orchard.tv.aerialvoice.START_LISTENING", true)
            .putExtra("app.orchard.tv.aerialvoice.CONTINUOUS_MODE", startContinuous)
            .putExtra("app.orchard.tv.aerialvoice.TRIGGER_SOURCE", "xiaomi_remote_f5")
        runCatching { startActivity(intent) }
            .onSuccess {
            Log.i("AerialVoiceBridge", "Xiaomi remote F5 opened Aerial Voice (continuous=$startContinuous)")
            DiagLog.i("voice bridge: Xiaomi remote F5 opened Aerial Voice (continuous=$startContinuous)")
        }
            .onFailure { Log.w("AerialVoiceBridge", "Could not open Aerial Voice; is app.orchard.tv.aerialvoice installed?", it) }
    }

'''
src = src.replace(key_anchor, key_handler + key_anchor, 1)

post_key_anchor = '''        if (!GuardSetup.isUserUnlocked(this)) return false
'''
if src.count(post_key_anchor) != 1:
    raise SystemExit("Unexpected HomeGuardService.kt: could not locate unlocked-user guard.")
src = src.replace(post_key_anchor, post_key_anchor + '''        if (event.keyCode == KeyEvent.KEYCODE_F5 && isAerialVoiceRemote(event)) {
            return handleAerialVoiceKey(event)
        }
''', 1)
service.write_text(src, encoding="utf-8")

xml = manifest.read_text(encoding="utf-8")
query = '<package android:name="app.orchard.tv.aerialvoice" />'
if query not in xml:
    q_anchor = "    <queries>\n"
    if xml.count(q_anchor) != 1:
        raise SystemExit("Unexpected AndroidManifest.xml: could not uniquely locate <queries> block.")
    xml = xml.replace(q_anchor, q_anchor + "        " + query + "\n", 1)
    manifest.write_text(xml, encoding="utf-8")

print("Aerial Voice bridge applied to Aerial v1.95.")
print("No changes were made to updateKeyFilter() or the existing mouse/gamepad safety checks.")
