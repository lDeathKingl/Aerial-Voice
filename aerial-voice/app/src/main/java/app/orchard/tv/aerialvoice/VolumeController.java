package app.orchard.tv.aerialvoice;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

/** Volume commands report the READ-BACK value; never claim success if TV ignores the API. */
public final class VolumeController {
    private static final String TAG="AerialVolume";
    private VolumeController(){}

    public static String setPercent(Context ctx, int requested) {
        if (requested < 0 || requested > 100) return "Громкость должна быть от 0 до 100 процентов.";
        AudioManager audio=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return "Android не предоставил AudioManager.";
        if (audio.isVolumeFixed()) return "Прошивка сообщает о фиксированной громкости; программное управление недоступно.";
        try {
            int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int before=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if(max<=0)return "Телевизор не предоставил диапазон громкости.";
            int goal=Math.round(max*requested/100f);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,goal,AudioManager.FLAG_SHOW_UI);
            int after=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if(after==before && after!=goal) {
                // Some firmwares reject stream-specific adjustments but allow default-stream changes.
                audio.adjustSuggestedStreamVolume(goal>after?AudioManager.ADJUST_RAISE:
                        AudioManager.ADJUST_LOWER,AudioManager.STREAM_MUSIC,AudioManager.FLAG_SHOW_UI);
                after=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            }
            return describe(before,after,max,requested,goal);
        } catch (RuntimeException failure) {
            Log.w(TAG,"Cannot set volume",failure);
            return "Телевизор отказал в изменении громкости: "+failure.getClass().getSimpleName();
        }
    }
    public static String step(Context ctx, boolean raise) {
        AudioManager audio=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
        if(audio==null)return "Системное управление громкостью недоступно.";
        if(audio.isVolumeFixed())return "Прошивка сообщает о фиксированной громкости.";
        try {
            int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int old=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int direction=raise?AudioManager.ADJUST_RAISE:AudioManager.ADJUST_LOWER;
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,direction,AudioManager.FLAG_SHOW_UI);
            int next=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if(old==next && (raise?old<max:old>0)) {
                audio.adjustSuggestedStreamVolume(direction,AudioManager.STREAM_MUSIC,AudioManager.FLAG_SHOW_UI);
                next=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            }
            if(next==old)return "Громкость не изменилась. Возможно, телевизор управляет звуком вне Android.";
            return "Android показывает изменение громкости: "+pct(old,max)+"% → "+pct(next,max)+"%. Звук HDMI/CEC нужно проверить на слух.";
        }catch(RuntimeException error){
            Log.w(TAG,"Volume adjustment failed",error);
            return "Команда громкости отклонена Android: "+error.getClass().getSimpleName();
        }
    }
    public static String mute(Context ctx,boolean muted) {
        AudioManager audio=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
        if(audio==null)return "Системное управление звуком недоступно.";
        if(audio.isVolumeFixed())return "Прошивка сообщает о фиксированной громкости.";
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    muted?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,
                    AudioManager.FLAG_SHOW_UI);
            return audio.isStreamMute(AudioManager.STREAM_MUSIC)==muted
                    ? muted?"Android сообщает, что звук выключен.":"Android сообщает, что звук включён."
                    : "Android не подтвердил изменение состояния звука.";
        }catch(RuntimeException e){return "Не удалось переключить звук: "+e.getClass().getSimpleName();}
    }
    public static String diagnostics(Context ctx){
        AudioManager a=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
        if(a==null)return "AudioManager недоступен";
        try {
            int max=a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return pct(a.getStreamVolume(AudioManager.STREAM_MUSIC),max)+"% (STREAM_MUSIC), fixed="+a.isVolumeFixed();
        }catch(RuntimeException e){return "Ошибка чтения "+e.getClass().getSimpleName();}
    }
    static String describe(int before,int after,int max,int requested,int goal){
        if(after==goal)return "Android показывает громкость "+pct(after,max)+"% (запрос "+requested+"%). Фактический звук HDMI/CEC не подтверждён.";
        if(after!=before)return "По данным Android громкость "+pct(after,max)+"%, а не "+requested+"%. Фактический звук HDMI/CEC не подтверждён.";
        return "Громкость НЕ изменилась (осталась "+pct(after,max)+"%). Прошивка, возможно, управляет звуком отдельно.";
    }
    static int pct(int value,int max){return max<=0?0:Math.round(100f*value/max);}
}
