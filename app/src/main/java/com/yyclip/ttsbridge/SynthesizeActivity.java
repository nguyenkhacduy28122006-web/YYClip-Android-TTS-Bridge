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

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class SynthesizeActivity extends Activity implements TextToSpeech.OnInitListener {

    private TextToSpeech tts;
    private String text, voice, audioName, statusName;
    private float rate = 1f, pitch = 1f;

    private Uri audioUri, statusUri;
    private ParcelFileDescriptor audioPfd;

    private final AtomicBoolean completed = new AtomicBoolean(false);

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        text = getIntent().getStringExtra("text");
        voice = getIntent().getStringExtra("voice");
        audioName = getIntent().getStringExtra("audio_name");
        statusName = getIntent().getStringExtra("status_name");

        rate = getIntent().getFloatExtra("rate", 1f);
        pitch = getIntent().getFloatExtra("pitch", 1f);

        // Create status first so later input or TTS errors can be reported.
        if (!validStatusName(statusName) || !prepareStatus()) {
            finishOnce("ERR_OUTPUT");
            return;
        }

        if (text == null || text.trim().isEmpty()
                || text.length() > 100000
                || !validAudioName(audioName)
                || !Float.isFinite(rate)
                || !Float.isFinite(pitch)) {
            finishOnce("ERR_INPUT");
            return;
        }

        rate = clamp(rate, 0.5f, 2.0f);
        pitch = clamp(pitch, 0.5f, 2.0f);

        if (!prepareAudio()) {
            finishOnce("ERR_OUTPUT");
            return;
        }

        try {
            tts = new TextToSpeech(this, this);
        } catch (Exception e) {
            finishOnce("ERR_INIT");
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean validAudioName(String name) {
        return name != null
                && name.matches("[A-Za-z0-9_\\-.]{1,100}")
                && name.toLowerCase(Locale.ROOT).endsWith(".wav");
    }

    private boolean validStatusName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,100}")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".status") || lower.endsWith(".txt");
    }

    @Override
    public void onInit(int status) {
        if (completed.get()) return;

        if (status != TextToSpeech.SUCCESS || tts == null) {
            finishOnce("ERR_INIT");
            return;
        }

        Locale locale;
        try {
            locale = Locale.forLanguageTag(
                    voice == null || voice.trim().isEmpty() ? "vi-VN" : voice
            );
            if (locale == null || locale.getLanguage().isEmpty()) {
                finishOnce("ERR_LANG");
                return;
            }
        } catch (Exception e) {
            finishOnce("ERR_LANG");
            return;
        }

        try {
            int langStatus = tts.setLanguage(locale);
            if (langStatus == TextToSpeech.LANG_MISSING_DATA
                    || langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                finishOnce("ERR_LANG");
                return;
            }

            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
        } catch (Exception e) {
            finishOnce("ERR_LANG");
            return;
        }

        try {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) { }

                @Override
                public void onDone(String utteranceId) {
                    runOnUiThread(() -> completeSynthesis());
                }

                @Override
                public void onError(String utteranceId) {
                    runOnUiThread(() -> finishOnce("ERR_SYNTH"));
                }

                @Override
                public void onError(String utteranceId, int errorCode) {
                    runOnUiThread(() -> finishOnce("ERR_SYNTH"));
                }
            });

            int result = tts.synthesizeToFile(
                    text,
                    new Bundle(),
                    audioPfd,
                    "yyclip-tts"
            );

            if (result == TextToSpeech.ERROR) {
                finishOnce("ERR_QUEUE");
            }
        } catch (Exception e) {
            finishOnce("ERR_QUEUE");
        }
    }

    private boolean prepareStatus() {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, statusName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/YYClip/"
            );

            statusUri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
            );
            return statusUri != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean prepareAudio() {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, audioName);
            values.put(MediaStore.Downloads.MIME_TYPE, "audio/wav");
            values.put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/YYClip/"
            );

            audioUri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
            );
            if (audioUri == null) return false;

            audioPfd = getContentResolver().openFileDescriptor(audioUri, "w");
            if (audioPfd == null) {
                deleteAudio();
                return false;
            }
            return true;
        } catch (Exception e) {
            closePfd();
            deleteAudio();
            return false;
        }
    }

    private void completeSynthesis() {
        if (completed.get()) return;

        closePfd();

        if (isValidWav()) {
            finishOnce("OK");
        } else {
            finishOnce("ERR_FORMAT");
        }
    }

    private boolean isValidWav() {
        if (audioUri == null) return false;

        try (InputStream in = getContentResolver().openInputStream(audioUri)) {
            if (in == null) return false;

            byte[] header = new byte[12];
            int total = 0;

            while (total < header.length) {
                int read = in.read(header, total, header.length - total);
                if (read < 0) return false;
                total += read;
            }

            return header[0] == 'R'
                    && header[1] == 'I'
                    && header[2] == 'F'
                    && header[3] == 'F'
                    && header[8] == 'W'
                    && header[9] == 'A'
                    && header[10] == 'V'
                    && header[11] == 'E';

        } catch (Exception e) {
            return false;
        }
    }

    private void finishOnce(String result) {
        if (!completed.compareAndSet(false, true)) return;

        closePfd();

        if (!"OK".equals(result)) {
            deleteAudio();
        }

        writeStatus(result);

        runOnUiThread(() -> {
            if (tts != null) {
                try {
                    tts.stop();
                } catch (Exception ignored) { }
            }
            finish();
        });
    }

    private void closePfd() {
        try {
            if (audioPfd != null) {
                audioPfd.close();
            }
        } catch (Exception ignored) { }
        audioPfd = null;
    }

    private void writeStatus(String value) {
        if (statusUri == null) return;

        try (OutputStream os = getContentResolver().openOutputStream(statusUri, "wt")) {
            if (os != null) {
                os.write(value.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (Exception e) {
            android.util.Log.e("YYClipBridge", "Cannot write status file", e);
        }
    }

    private void deleteAudio() {
        try {
            if (audioUri != null) {
                getContentResolver().delete(audioUri, null, null);
            }
        } catch (Exception ignored) { }
        audioUri = null;
    }

    private void deleteStatus() {
        try {
            if (statusUri != null) {
                getContentResolver().delete(statusUri, null, null);
            }
        } catch (Exception ignored) { }
        statusUri = null;
    }

    @Override
    public void onDestroy() {
        closePfd();

        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) { }
            tts = null;
        }

        super.onDestroy();
    }
}
