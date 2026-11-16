package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;

/** Open the existing assistant UI without silently replacing launcher or wake-word services. */
public final class AerialVoiceSession extends VoiceInteractionSession {
    public AerialVoiceSession(Context context) { super(context); }
    @Override public void onShow(Bundle args, int flags) {
        super.onShow(args, flags);
        try {
            Intent open=new Intent(getContext(),MainActivity.class)
                    .setAction(Intent.ACTION_ASSIST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            getContext().startActivity(open);
        } catch(RuntimeException error) {
            Log.e("AerialVoiceSession","System assistant launch unavailable",error);
        } finally { finish(); }
    }
}
