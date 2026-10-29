package net.kdt.pojavlaunch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tasks.AsyncAssetManager;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;

/** Experimental entry point; no Minecraft account or Termux required. */
public class ElorinActivity extends Activity {
    private TextView status;
    private Button play;
    private static final String RUNTIME = "Elorin17";
    private static final String HOST = "d1qnpp7kq86xt0.cloudfront.net";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 48, 32, 32);
        TextView title = new TextView(this);
        title.setText("Elorin • Android experiment"); title.setTextSize(24);
        layout.addView(title);
        status = new TextView(this);
        status.setText("Desktop compatibility build. Gameplay is unverified. First launch downloads the game from Elorin. Use the in-game display controls for keyboard and right-click.");
        layout.addView(status);
        play = new Button(this); play.setText("Prepare and play"); layout.addView(play);
        Button logs = new Button(this); logs.setText("Share diagnostic log"); layout.addView(logs);
        logs.setOnClickListener(v -> shareLog());
        setContentView(layout);
        play.setOnClickListener(v -> {
            play.setEnabled(false);
            new Thread(() -> {
                try { prepareAndLaunch(); }
                catch (Exception e) { message("Launch setup failed: " + e.toString()); }
                finally { runOnUiThread(() -> play.setEnabled(true)); }
            }, "elorin-prepare").start();
        });
    }
    private void message(String text) { runOnUiThread(() -> status.setText(text)); }
    private void prepareAndLaunch() throws Exception {
        if (!Tools.checkStorageRoot(this)) throw new IOException("App storage is unavailable");
        LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation", 768).putBoolean("arc_capes", false).apply();
        LauncherPreferences.loadPreferences(this);
        File working = new File(Tools.DIR_GAME_NEW);
        if (!working.isDirectory() && !working.mkdirs()) throw new IOException("Cannot create working directory");
        File marker = new File(getFilesDir(), "runtime17-ready-v1");
        if (!marker.isFile() || MultiRTUtils.forceReread(RUNTIME) == null) {
            message("Installing bundled Java runtime…");
            try (InputStream in = getAssets().open("elorin/jre17-arm64.tar.xz")) {
                MultiRTUtils.installRuntimeNamed(getApplicationInfo().nativeLibraryDir, in, RUNTIME);
            }
            MultiRTUtils.postPrepare(RUNTIME);
            if (!marker.createNewFile() && !marker.isFile()) throw new IOException("Cannot save runtime state");
        }
        AsyncAssetManager.prepareElorin(this);
        LauncherPreferences.DEFAULT_PREF.edit().putString("defaultRuntime", RUNTIME).apply();
        LauncherPreferences.PREF_DEFAULT_RUNTIME = RUNTIME;
        File gameDir = new File(getFilesDir(), "elorin-game");
        if (!gameDir.isDirectory() && !gameDir.mkdirs()) throw new IOException("Cannot create game directory");
        String manifest;
        try (InputStream in = getAssets().open("elorin/bootstrap.json")) { manifest = read(in); }
        JSONArray artifacts = new JSONObject(manifest).getJSONArray("artifacts");
        StringBuilder classpath = new StringBuilder();
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject artifact = artifacts.getJSONObject(i);
            // Desktop native binaries cannot be loaded by Android. Start with CPU rendering.
            if (artifact.has("platform")) continue;
            String name = artifact.getString("name");
            if (!name.matches("[A-Za-z0-9_.-]+\\.jar")) throw new IOException("Invalid artifact name");
            File target = new File(gameDir, name);
            String hash = artifact.getString("hash");
            long size = artifact.getLong("size");
            if (!valid(target, size, hash)) {
                message("Downloading " + name);
                URL url = new URL(artifact.getString("path"));
                if (!"https".equals(url.getProtocol()) || !HOST.equals(url.getHost())) throw new IOException("Unexpected download host");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
                File part = new File(gameDir, name + ".part");
                try {
                    if (connection.getResponseCode() != 200) throw new IOException("Download HTTP " + connection.getResponseCode());
                    try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                        byte[] buffer = new byte[65536]; int count; long received = 0;
                        while ((count = in.read(buffer)) != -1) {
                            received += count;
                            if (received > size) throw new IOException("Unexpected artifact size");
                            out.write(buffer, 0, count);
                        }
                    }
                    if (!valid(part, size, hash)) throw new IOException("Checksum mismatch: " + name);
                    if (!part.renameTo(target)) throw new IOException("Cannot install " + name);
                } finally { connection.disconnect(); part.delete(); }
            }
            if (classpath.length() > 0) classpath.append(':');
            classpath.append(target.getAbsolutePath());
        }
        String args = "-Xmx768m -Xss2m -Dsun.java2d.opengl=false --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED -cp " + classpath + " Main";
        message("Starting Elorin. If it fails, use Share diagnostic log.");
        runOnUiThread(() -> startActivity(new Intent(this, JavaGUILauncherActivity.class).putExtra("javaArgs", args)));
    }
    private static boolean valid(File file, long size, String hash) throws Exception {
        if (!file.isFile() || file.length() != size) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
        return hash.equals(hex.toString());
    }
    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toString("UTF-8");
    }
    private void shareLog() {
        try {
            File log = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            String text;
            try (RandomAccessFile in = new RandomAccessFile(log, "r")) {
                long length = in.length();
                int headLength = (int)Math.min(length, 40000);
                byte[] head = new byte[headLength]; in.readFully(head);
                text = new String(head, "UTF-8");
                if (length > headLength) {
                    long tailStart = Math.max(headLength, length - 60000);
                    in.seek(tailStart);
                    byte[] tail = new byte[(int)(length - tailStart)]; in.readFully(tail);
                    if (tailStart > headLength) text += "\n\n[Middle omitted; startup and final errors preserved]\n\n";
                    text += new String(tail, "UTF-8");
                }
            }
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Elorin Android diagnostic")
                    .putExtra(Intent.EXTRA_TEXT, text), "Share diagnostic log"));
        } catch (Exception e) { message("No runtime log yet. " + e.getMessage()); }
    }
}
