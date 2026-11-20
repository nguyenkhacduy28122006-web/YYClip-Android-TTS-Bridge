package com.yyclip.ttsbridge;
import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.OutputStream;
import java.util.Locale;
public class SynthesizeActivity extends Activity implements TextToSpeech.OnInitListener {
 private TextToSpeech tts; private String text, voice, audioName, statusName; private float rate=1f,pitch=1f; private Uri audioUri,statusUri; private ParcelFileDescriptor audioPfd;
 @Override public void onCreate(Bundle state){super.onCreate(state); text=getIntent().getStringExtra("text");voice=getIntent().getStringExtra("voice");audioName=getIntent().getStringExtra("audio_name");statusName=getIntent().getStringExtra("status_name");rate=getIntent().getFloatExtra("rate",1f);pitch=getIntent().getFloatExtra("pitch",1f);if(text==null||audioName==null||statusName==null){finish();return;}tts=new TextToSpeech(this,this);}
 @Override public void onInit(int status){if(status!=TextToSpeech.SUCCESS){writeStatus("ERR_INIT");finish();return;}Locale locale=Locale.forLanguageTag(voice==null?"vi-VN":voice);int ls=tts.setLanguage(locale);if(ls==TextToSpeech.LANG_MISSING_DATA||ls==TextToSpeech.LANG_NOT_SUPPORTED){writeStatus("ERR_LANG");finish();return;}tts.setSpeechRate(rate);tts.setPitch(pitch);if(!prepareOutput()){writeStatus("ERR_OUTPUT");finish();return;}tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){@Override public void onStart(String id){}@Override public void onDone(String id){writeStatus("OK");closePfd();runOnUiThread(()->finish());}@Override public void onError(String id){writeStatus("ERR_SYNTH");closePfd();runOnUiThread(()->finish());}});int q=tts.synthesizeToFile(text,new Bundle(),audioPfd,"yyclip-tts");if(q==TextToSpeech.ERROR){writeStatus("ERR_QUEUE");closePfd();finish();}}
 private boolean prepareOutput(){try{ContentValues a=new ContentValues();a.put(MediaStore.Downloads.DISPLAY_NAME,audioName);a.put(MediaStore.Downloads.MIME_TYPE,"audio/wav");a.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/YYClip/");audioUri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,a);ContentValues st=new ContentValues();st.put(MediaStore.Downloads.DISPLAY_NAME,statusName);st.put(MediaStore.Downloads.MIME_TYPE,"text/plain");st.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/YYClip/");statusUri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,st);audioPfd=getContentResolver().openFileDescriptor(audioUri,"w");return audioUri!=null&&statusUri!=null&&audioPfd!=null;}catch(Exception e){return false;}}
 private void closePfd(){try{if(audioPfd!=null)audioPfd.close();}catch(Exception ignored){}}
 private void writeStatus(String value){try(OutputStream os=getContentResolver().openOutputStream(statusUri,"wt")){if(os!=null)os.write(value.getBytes("UTF-8"));}catch(Exception ignored){}}
 @Override public void onDestroy(){closePfd();if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}
