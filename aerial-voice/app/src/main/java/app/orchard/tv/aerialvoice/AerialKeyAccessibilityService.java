package app.orchard.tv.aerialvoice;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.util.Log;

/**
 * Optional system-wide F5/PTT key bridge. The user must explicitly enable the
 * Accessibility Service; it never enables itself or modifies secure settings.
 */
public final class AerialKeyAccessibilityService extends AccessibilityService {
    private static final String TAG="AerialKeyBridge";
    private boolean pressed;

    @Override protected boolean onKeyEvent(KeyEvent event) {
        int code=event.getKeyCode();
        if(code!=KeyEvent.KEYCODE_F5 && code!=KeyEvent.KEYCODE_VOICE_ASSIST
                && code!=KeyEvent.KEYCODE_ASSIST)return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0) {
            if(pressed)return true;
            pressed=true;
            send(true);
            return true;
        }
        if(event.getAction()==KeyEvent.ACTION_UP) {
            if(pressed){pressed=false;send(false);}
            return true;
        }
        return pressed;
    }
    private void send(boolean down){
        try {
            Intent intent=new Intent(this,MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(down?"app.orchard.tv.aerialvoice.START_LISTENING":
                    "app.orchard.tv.aerialvoice.STOP_LISTENING",true);
            startActivity(intent);
        }catch(RuntimeException error){
            Log.w(TAG,"Global key not delivered; ROM may intercept",error);
        }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override protected void onServiceConnected(){super.onServiceConnected();pressed=false;}
}
