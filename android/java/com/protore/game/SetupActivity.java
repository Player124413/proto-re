package com.protore.game;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First-launch screen: creates the game folder, checks that the files copied from the PC version are really
 * there (and can copy them itself from a folder the player picks, through the system file picker), picks a
 * conservative quality preset for the device, optionally imports a Mesa Turnip driver ZIP for Adreno,
 * and backs up the saves (the emulated C: drive in native_c/) before the game starts. The game itself
 * still runs in ProtoActivity, unchanged.
 *
 * Quality presets share their storage with the in-game settings panel (TouchControls): engine values go to
 * protore.ini, the matching setting indices go to the "controls" preferences, so the two never fight.
 */
public class SetupActivity extends Activity {

    // Engine setting tables, mirroring TouchControls (preferences store the index into these).
    private static final int[] RENDER_VALUES = {540, 720, 900, 1080};
    private static final int[] TEXTURE_VALUES = {0, 1, 2};
    private static final int[] FPS_VALUES = {0, 30, 45, 60};
    private static final String[] ENGINE_KEYS = {"render_height", "texture_skip_mips", "fps_limit", "battery_saver"};
    //                     render, textures, fps, battery_saver (indices)
    private static final int[][] PRESETS = {
            {0, 2, 1, 1},  // Low:    540p, quarter textures, 30 fps, battery saver
            {1, 1, 3, 0},  // Medium: 720p, half textures, 60 fps
            {2, 0, 3, 0},  // High:   900p, full textures, 60 fps
    };
    private static final String[] PRESET_NAMES = {"Low", "Medium", "High"};
    private static final String[] PRESET_BLURB = {
            "540p, quarter textures, 30 fps, battery saver",
            "720p, half textures, 60 fps",
            "900p, full textures, 60 fps",
    };
    private static final int MAX_BACKUPS = 5;

    private static final int INK = 0xff0b0b0b, RED = 0xffe0261e, TEXT = 0xffe8d6d4, DIM = 0xffa89895;
    private static final String OK = "\u2713 ", NO = "\u2717 ", WARN = "! ";

    private static final int REQ_PICK_GAME_FOLDER = 4101;
    private static final int REQ_PICK_TURNIP_ZIP = 4102;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private File baseDir, gameDir, iniFile, turnipDir;
    private TextView status, presetInfo, turnipInfo, backupInfo, importStatus;
    private Button play, importButton, turnipImportButton, turnipRemoveButton;
    private ProgressBar importProgress;
    private final Button[] presetButtons = new Button[PRESET_NAMES.length];
    private volatile boolean backupRunning, importRunning, importCancelled, driverImportRunning;
    private boolean dllOk;
    private int autoPreset = 1;
    private long totalRamBytes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        baseDir = getExternalFilesDir(null);
        turnipDir = TurnipDriverPackage.dir(this);  // internal storage: drivers cannot dlopen from /sdcard
        buildUi();
        if (baseDir == null) {
            status.setText(NO + "External storage is unavailable, the game folder cannot be created.");
            play.setEnabled(false);
            return;
        }
        gameDir = new File(baseDir, "game");
        iniFile = new File(baseDir, "protore.ini");
        autoPreset = pickAutoPreset();
        if (!iniFile.exists()) applyPreset(autoPreset);  // first launch: sensible starting point for this device
        refresh();
        maybeBackupSaves(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (baseDir != null) refresh();  // the player may have copied files while this screen was up
    }

    @Override
    protected void onDestroy() {
        importCancelled = true;
        io.shutdownNow();
        super.onDestroy();
    }

    // ---- UI (built in code; the project keeps its resources to the launcher icon) ----

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        return t;
    }

    private Button button(String s, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(onClick);
        return b;
    }

    private void add(LinearLayout root, View v, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(topMarginDp), 0, 0);
        root.addView(v, lp);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(INK);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));

        add(root, text("PROTOTYPE", 34, RED, true), 0);
        add(root, text("proto-re Android port - setup", 16, DIM, false), 2);
        add(root, text("1. Get the game files in place: tap Choose game folder and pick the PC installation "
                + "folder (the needed files are copied here automatically), or copy manually into the folder "
                + "shown below: prototypeenginef.dll, all .rcf and .p3d files, and the art, audio, scripts folders.\n"
                + "2. Pick a quality preset (it can also be changed in game).\n"
                + "3. On Adreno, optionally import a Turnip driver ZIP.\n"
                + "4. Press Play.", 15, TEXT, false), 14);

        status = text("", 15, TEXT, false);
        status.setTypeface(Typeface.MONOSPACE);
        add(root, status, 14);

        importButton = button("Choose game folder...", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickGameFolder();
            }
        });
        add(root, importButton, 14);
        importProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        importProgress.setMax(1000);
        importProgress.setVisibility(View.GONE);
        add(root, importProgress, 8);
        importStatus = text("", 13, DIM, false);
        importStatus.setVisibility(View.GONE);
        add(root, importStatus, 4);

        play = button("Play", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (importRunning) {
                    Toast.makeText(SetupActivity.this, "Importing game files...", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (driverImportRunning) {
                    Toast.makeText(SetupActivity.this, "Importing Turnip driver...", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (backupRunning) {
                    Toast.makeText(SetupActivity.this, "Backing up saves...", Toast.LENGTH_SHORT).show();
                    return;
                }
                startActivity(new Intent(SetupActivity.this, ProtoActivity.class));
            }
        });
        play.setEnabled(false);
        add(root, play, 14);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button recheck = button("Recheck files", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        Button copyPath = button("Copy folder path", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (gameDir == null) return;
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Prototype game folder", gameDir.getAbsolutePath()));
                }
                Toast.makeText(SetupActivity.this, "Path copied", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(recheck, half);
        row.addView(copyPath, half);
        add(root, row, 6);

        add(root, text("Quality preset", 20, RED, true), 20);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            final int idx = i;
            presetButtons[i] = button(PRESET_NAMES[i], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyPreset(idx);
                    updatePresetUi();
                    Toast.makeText(SetupActivity.this,
                            PRESET_NAMES[idx] + " preset (applies when the game starts)",
                            Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams third = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            presets.addView(presetButtons[i], third);
        }
        add(root, presets, 6);
        presetInfo = text("", 14, DIM, false);
        add(root, presetInfo, 6);

        add(root, text("Turnip driver (Adreno)", 20, RED, true), 20);
        turnipInfo = text("", 14, DIM, false);
        add(root, turnipInfo, 6);
        LinearLayout driverRow = new LinearLayout(this);
        driverRow.setOrientation(LinearLayout.HORIZONTAL);
        turnipImportButton = button("Import Turnip ZIP...", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTurnipZip();
            }
        });
        turnipRemoveButton = button("Use bundled Turnip", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeImportedTurnip();
            }
        });
        LinearLayout.LayoutParams driverHalf = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        driverRow.addView(turnipImportButton, driverHalf);
        driverRow.addView(turnipRemoveButton, driverHalf);
        add(root, driverRow, 6);

        add(root, text("Saves", 20, RED, true), 20);
        backupInfo = text("", 14, DIM, false);
        add(root, backupInfo, 6);
        add(root, button("Back up saves now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                maybeBackupSaves(true);
            }
        }), 6);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(INK);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ---- file checks ----

    private void refresh() {
        if (gameDir == null) return;
        //noinspection ResultOfMethodCallIgnored
        gameDir.mkdirs();

        File dll = new File(gameDir, "prototypeenginef.dll");
        dllOk = isPeFile(dll);
        int rcf = countExt(".rcf"), p3d = countExt(".p3d");
        StringBuilder s = new StringBuilder();
        s.append("Game folder:\n").append(gameDir.getAbsolutePath()).append("\n\n");
        s.append(dllOk ? OK : NO).append("prototypeenginef.dll");
        if (dll.isFile()) {
            s.append(String.format(Locale.US, " (%.1f MB%s)", dll.length() / 1048576f, dllOk ? "" : ", not a PE file"));
        }
        s.append('\n');
        s.append(rcf > 0 ? OK : WARN).append(rcf).append(" .rcf archives\n");
        s.append(p3d > 0 ? OK : WARN).append(p3d).append(" .p3d files\n");
        for (String dir : new String[] {"art", "audio", "scripts"}) {
            s.append(dirReady(dir) ? OK : WARN).append(dir).append("/ folder\n");
        }
        StatFs fs = new StatFs(baseDir.getPath());
        s.append(String.format(Locale.US, "\nFree space: %.1f GB", fs.getAvailableBytes() / 1073741824f));
        status.setText(s.toString());

        play.setEnabled(dllOk && !importRunning && !driverImportRunning);
        play.setText(dllOk ? "Play" : "Copy the game files first");
        updatePresetUi();
        updateTurnipUi();
        updateBackupInfo();
    }

    /** Hard requirement, same as the native host: a readable 32-bit PE named prototypeenginef.dll. */
    private boolean isPeFile(File f) {
        if (!f.isFile() || f.length() < 2) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            return in.read() == 'M' && in.read() == 'Z';
        } catch (IOException e) {
            return false;
        }
    }

    private int countExt(String ext) {
        File[] files = gameDir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(ext)) n++;
        }
        return n;
    }

    private boolean dirReady(String name) {
        File[] files = new File(gameDir, name).listFiles();
        return files != null && files.length > 0;
    }

    // ---- quality presets ----

    /** Conservative on purpose (heat first): High is never picked automatically. */
    private int pickAutoPreset() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            totalRamBytes = mi.totalMem;
        }
        return totalRamBytes > 0 && totalRamBytes < (6L << 30) ? 0 : 1;
    }

    private Map<String, Integer> readIniValues() {
        Map<String, Integer> values = new HashMap<>();
        try {
            if (iniFile.exists()) {
                for (String line : new String(Files.readAllBytes(iniFile.toPath()), StandardCharsets.UTF_8)
                        .split("\n")) {
                    String[] kv = line.split("=", 2);
                    if (kv.length != 2) continue;
                    try {
                        values.put(kv[0].trim().toLowerCase(Locale.US),
                                Integer.parseInt(kv[1].split("[#;]")[0].trim()));
                    } catch (NumberFormatException ignored) {
                        // not a numeric key; writeEngineValues() keeps the line as it is
                    }
                }
            }
        } catch (Exception ignored) {
            // a broken ini falls back to the preference/default values below
        }
        return values;
    }

    private static int indexOf(int[] table, int value, int fallback) {
        for (int i = 0; i < table.length; i++) if (table[i] == value) return i;
        return fallback;
    }

    /** Current engine settings as TouchControls indices: protore.ini wins, then preferences, then defaults. */
    private int[] currentIndices() {
        SharedPreferences prefs = getSharedPreferences("controls", Context.MODE_PRIVATE);
        int[] idx = {
                prefs.getInt("render_height", 1),
                prefs.getInt("texture_skip_mips", 1),
                prefs.getInt("fps_limit", 0),
                prefs.getInt("battery_saver", 0),
        };
        Map<String, Integer> ini = readIniValues();
        if (ini.containsKey("render_height")) idx[0] = indexOf(RENDER_VALUES, ini.get("render_height"), idx[0]);
        if (ini.containsKey("texture_skip_mips")) {
            idx[1] = indexOf(TEXTURE_VALUES, ini.get("texture_skip_mips"), idx[1]);
        }
        if (ini.containsKey("fps_limit")) idx[2] = indexOf(FPS_VALUES, ini.get("fps_limit"), idx[2]);
        if (ini.containsKey("battery_saver")) idx[3] = ini.get("battery_saver") != 0 ? 1 : 0;
        return idx;
    }

    private void applyPreset(int preset) {
        int[] idx = PRESETS[preset];
        Map<String, Integer> values = new HashMap<>();
        values.put("render_height", RENDER_VALUES[idx[0]]);
        values.put("texture_skip_mips", TEXTURE_VALUES[idx[1]]);
        values.put("fps_limit", FPS_VALUES[idx[2]]);
        values.put("battery_saver", idx[3]);
        writeIniValues(values);
        getSharedPreferences("controls", Context.MODE_PRIVATE).edit()
                .putInt("render_height", idx[0])
                .putInt("texture_skip_mips", idx[1])
                .putInt("fps_limit", idx[2])
                .putInt("battery_saver", idx[3])
                .apply();
    }

    /** Rewrites our engine keys in protore.ini keeping every other line (same as TouchControls does). */
    private void writeIniValues(Map<String, Integer> values) {
        StringBuilder out = new StringBuilder();
        try {
            if (iniFile.exists()) {
                for (String line : new String(Files.readAllBytes(iniFile.toPath()), StandardCharsets.UTF_8)
                        .split("\n")) {
                    if (!line.trim().isEmpty()
                            && !values.containsKey(line.split("=", 2)[0].trim().toLowerCase(Locale.US))) {
                        out.append(line).append('\n');
                    }
                }
            }
            for (String key : ENGINE_KEYS) out.append(key).append(" = ").append(values.get(key)).append('\n');
            try (FileWriter w = new FileWriter(iniFile)) {
                w.write(out.toString());
            }
        } catch (Exception ignored) {
            // the in-game panel can still write the ini later; the native defaults apply meanwhile
        }
    }

    private void updatePresetUi() {
        int[] cur = currentIndices();
        int selected = -1;
        for (int i = 0; i < PRESETS.length; i++) {
            boolean match = true;
            for (int j = 0; j < cur.length; j++) match &= PRESETS[i][j] == cur[j];
            if (match) selected = i;
        }
        for (int i = 0; i < presetButtons.length; i++) {
            presetButtons[i].setText(PRESET_NAMES[i] + (i == selected ? " \u2713" : ""));
            presetButtons[i].setTextColor(i == selected ? RED : TEXT);
        }
        String ram = totalRamBytes > 0
                ? String.format(Locale.US, "%.0f GB RAM", totalRamBytes / 1073741824f) : "unknown RAM";
        presetInfo.setText("Current: " + (selected >= 0
                        ? PRESET_NAMES[selected] + " (" + PRESET_BLURB[selected] + ")"
                        : "Custom (" + RENDER_VALUES[cur[0]] + "p, texture step " + TEXTURE_VALUES[cur[1]]
                        + ", fps limit " + (FPS_VALUES[cur[2]] == 0 ? "off" : FPS_VALUES[cur[2]])
                        + (cur[3] != 0 ? ", battery saver" : "") + ")")
                + "\nAuto choice on first launch: " + PRESET_NAMES[autoPreset] + " for this device (" + ram
                + ") - conservative against heat; High is manual. Presets apply when the game (re)starts, "
                + "the same values can be tuned in the in-game settings panel.");
    }

    // ---- imported Turnip driver (Adreno): an AdrenoTools ZIP in internal storage ----

    private void updateTurnipUi() {
        if (turnipInfo == null || turnipImportButton == null || turnipRemoveButton == null) return;
        boolean installedDir = turnipDir != null && turnipDir.isDirectory();
        boolean adreno = new File("/dev/kgsl-3d0").exists();
        TurnipDriverPackage.Meta meta = null;
        String error = null;
        if (installedDir) {
            try {
                meta = TurnipDriverPackage.readInstalled(turnipDir);
            } catch (Exception e) {
                error = e.getMessage();
            }
        }

        String text;
        if (driverImportRunning) {
            text = "Working on the Turnip driver...";
        } else if (meta != null) {
            File main = TurnipDriverPackage.mainLibrary(turnipDir, meta);
            String size = main == null ? ""
                    : String.format(Locale.US, " (%.1f MB)", main.length() / 1048576f);
            text = OK + "Imported driver: " + meta.label()
                    + "\nLibrary: " + meta.libraryName + size
                    + (meta.minApi > 0 ? ", min API " + meta.minApi : "")
                    + "\n" + (adreno
                    ? "It replaces the APK's bundled Turnip on the next game start. If it does not load, "
                    + "the game falls back to the bundled/system driver (see proto-native.log)."
                    : "No Adreno/KGSL device was detected, so this driver will not be used here.");
        } else if (installedDir) {
            text = WARN + "The imported driver folder is incomplete or invalid"
                    + (error == null || error.isEmpty() ? "" : ": " + error)
                    + ". The bundled/system driver will be used; import another ZIP or tap "
                    + "\"Use bundled Turnip\".";
        } else {
            text = "Using the Turnip driver bundled in this APK on Adreno (the system driver elsewhere)."
                    + "\nImport accepts a standard AdrenoTools Mesa Turnip ZIP (.zip/.adpkg.zip: "
                    + "meta.json + libvulkan_freedreno.so). Do not unzip it first. Only import native "
                    + "code from a source you trust."
                    + (adreno ? "" : "\nNo Adreno/KGSL device was detected on this phone.");
        }
        turnipInfo.setText(text);
        turnipImportButton.setEnabled(!driverImportRunning && !importRunning);
        turnipRemoveButton.setEnabled(installedDir && !driverImportRunning && !importRunning);
        turnipRemoveButton.setText(installedDir ? "Use bundled Turnip" : "Bundled Turnip in use");
    }

    private void pickTurnipZip() {
        if (driverImportRunning || importRunning) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_TURNIP_ZIP);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker on this device", Toast.LENGTH_LONG).show();
        }
    }

    private void startTurnipImport(final Uri zipUri) {
        if (driverImportRunning || importRunning || turnipDir == null) return;
        driverImportRunning = true;
        play.setEnabled(false);
        updateTurnipUi();
        io.execute(new Runnable() {
            @Override
            public void run() {
                String message;
                try (InputStream in = getContentResolver().openInputStream(zipUri)) {
                    TurnipDriverPackage.Meta meta = TurnipDriverPackage.install(
                            in, turnipDir, Build.VERSION.SDK_INT);
                    clearTurnipFailureMarkers();
                    boolean changedMode = ensureImportedDriverWillBeTried();
                    message = "Turnip driver imported: " + meta.label()
                            + ". It will be tried on the next game start."
                            + (changedMode
                            ? " protore.ini had vulkan_driver = system, so it was changed to auto." : "");
                } catch (Exception e) {
                    message = "Turnip import failed: " + String.valueOf(e.getMessage());
                }
                final String result = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        driverImportRunning = false;
                        Toast.makeText(SetupActivity.this, result, Toast.LENGTH_LONG).show();
                        refresh();
                    }
                });
            }
        });
    }

    private void removeImportedTurnip() {
        if (driverImportRunning || importRunning || turnipDir == null || !turnipDir.exists()) return;
        driverImportRunning = true;
        play.setEnabled(false);
        updateTurnipUi();
        io.execute(new Runnable() {
            @Override
            public void run() {
                TurnipDriverPackage.deleteRecursive(turnipDir);
                clearTurnipFailureMarkers();
                final String result = turnipDir.exists()
                        ? "Could not remove the imported Turnip driver"
                        : "Imported Turnip driver removed; the bundled Turnip will be used";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        driverImportRunning = false;
                        Toast.makeText(SetupActivity.this, result, Toast.LENGTH_LONG).show();
                        refresh();
                    }
                });
            }
        });
    }

    /** A different imported driver deserves a fresh try even if another one crashed during startup. */
    private void clearTurnipFailureMarkers() {
        if (baseDir == null) return;
        deleteRecursive(new File(baseDir, "turnip_failed"));
        deleteRecursive(new File(baseDir, "turnip_pending"));
    }

    /** Importing a driver while protore.ini pins the system driver would silently do nothing. */
    private boolean ensureImportedDriverWillBeTried() {
        return "system".equalsIgnoreCase(readIniString("vulkan_driver"))
                && writeIniString("vulkan_driver", "auto");
    }

    private String readIniString(String key) {
        try {
            if (iniFile == null || !iniFile.exists()) return "";
            for (String rawLine : new String(Files.readAllBytes(iniFile.toPath()), StandardCharsets.UTF_8)
                    .split("\n")) {
                String line = rawLine;
                int hash = line.indexOf('#');
                int semi = line.indexOf(';');
                int cut = hash < 0 ? semi : (semi < 0 ? hash : Math.min(hash, semi));
                if (cut >= 0) line = line.substring(0, cut);
                String[] kv = line.split("=", 2);
                if (kv.length == 2 && key.equalsIgnoreCase(kv[0].trim())) return kv[1].trim();
            }
        } catch (Exception ignored) {
            // the native side falls back to auto when the ini cannot be read
        }
        return "";
    }

    private boolean writeIniString(String key, String value) {
        if (iniFile == null) return false;
        StringBuilder out = new StringBuilder();
        try {
            if (iniFile.exists()) {
                for (String line : new String(Files.readAllBytes(iniFile.toPath()), StandardCharsets.UTF_8)
                        .split("\n")) {
                    String[] kv = line.split("=", 2);
                    if (kv.length != 2 || !key.equalsIgnoreCase(kv[0].trim())) {
                        out.append(line).append('\n');
                    }
                }
            }
            out.append(key).append(" = ").append(value).append('\n');
            try (FileWriter w = new FileWriter(iniFile)) {
                w.write(out.toString());
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ---- save backups (native_c/: the emulated C: drive with the engine's saves and settings) ----

    private void updateBackupInfo() {
        int n = 0;
        File[] dirs = new File(baseDir, "backups").listFiles();
        if (dirs != null) {
            for (File d : dirs) if (d.isDirectory() && d.getName().startsWith("native_c-")) n++;
        }
        backupInfo.setText("Save backups: " + n + " (files/backups, last " + MAX_BACKUPS + " kept). "
                + "A backup is made automatically when the saves changed since the last one.");
    }

    private void maybeBackupSaves(boolean force) {
        if (backupRunning || baseDir == null) return;
        final File saves = new File(baseDir, "native_c");
        if (!saves.isDirectory()) {
            if (force) Toast.makeText(this, "No saves yet (native_c/ does not exist)", Toast.LENGTH_SHORT).show();
            return;
        }
        final long newest = lastModifiedRecursive(saves);
        if (newest == 0) return;
        SharedPreferences prefs = getSharedPreferences("setup", Context.MODE_PRIVATE);
        if (!force && newest <= prefs.getLong("last_backup_source_mtime", 0)) return;
        backupRunning = true;
        io.execute(new Runnable() {
            @Override
            public void run() {
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                File backupsRoot = new File(baseDir, "backups");
                File dest = new File(backupsRoot, "native_c-" + stamp);
                String toast;
                try {
                    copyRecursive(saves, dest);
                    pruneBackups(backupsRoot);
                    getSharedPreferences("setup", Context.MODE_PRIVATE).edit()
                            .putLong("last_backup_source_mtime", newest).apply();
                    toast = "Saves backed up";
                } catch (IOException e) {
                    deleteRecursive(dest);  // never leave a half-written backup behind
                    toast = "Save backup failed: " + e.getMessage();
                }
                backupRunning = false;
                final String message = toast;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(SetupActivity.this, message, Toast.LENGTH_SHORT).show();
                        updateBackupInfo();
                    }
                });
            }
        });
    }

    private static long lastModifiedRecursive(File root) {
        long newest = 0;
        List<File> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            File f = stack.remove(stack.size() - 1);
            newest = Math.max(newest, f.lastModified());
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) stack.add(c);
            }
        }
        return newest;
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.mkdirs() && !dst.isDirectory()) throw new IOException("cannot create " + dst);
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) copyRecursive(c, new File(dst, c.getName()));
            }
            return;
        }
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void pruneBackups(File backupsRoot) {
        File[] dirs = backupsRoot.listFiles();
        if (dirs == null) return;
        List<File> backups = new ArrayList<>();
        for (File d : dirs) if (d.isDirectory() && d.getName().startsWith("native_c-")) backups.add(d);
        backups.sort(new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());  // timestamps sort lexicographically
            }
        });
        for (int i = MAX_BACKUPS; i < backups.size(); i++) deleteRecursive(backups.get(i));
    }


    // ---- import: pick the PC installation folder, copy what the port needs into game/ ----

    private void pickGameFolder() {
        if (importRunning || driverImportRunning || gameDir == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_GAME_FOLDER);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker on this device - copy the files manually",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // the temporary grant from the picker is enough for this import
        }
        if (requestCode == REQ_PICK_GAME_FOLDER) {
            startImport(uri);
        } else if (requestCode == REQ_PICK_TURNIP_ZIP) {
            startTurnipImport(uri);
        }
    }

    private static final class ChildDoc {
        String id, name, mime;
        long size;

        boolean isDir() {
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
        }
    }

    private static final class CopyItem {
        final Uri uri;
        final File dest;
        final long size;

        CopyItem(Uri uri, File dest, long size) {
            this.uri = uri;
            this.dest = dest;
            this.size = size;
        }
    }

    private static boolean safeName(String name) {
        return !name.isEmpty() && !name.contains("/") && !name.contains("\\")
                && !name.equals(".") && !name.equals("..");
    }

    private List<ChildDoc> listChildren(Uri treeUri, String docId) {
        List<ChildDoc> out = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
        };
        try (Cursor c = getContentResolver().query(children, projection, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                ChildDoc d = new ChildDoc();
                d.id = c.getString(0);
                d.name = c.getString(1);
                d.mime = c.getString(2);
                d.size = c.isNull(3) ? -1 : c.getLong(3);
                if (d.id != null && d.name != null && safeName(d.name)) out.add(d);
            }
        } catch (RuntimeException e) {
            // provider died or the grant is gone: report as empty, the summary below will say so
        }
        return out;
    }

    /** The picked folder itself, or a direct subfolder of it, that holds prototypeenginef.dll; null if none. */
    private String findGameRoot(Uri treeUri, String rootId) {
        List<ChildDoc> top = listChildren(treeUri, rootId);
        for (ChildDoc d : top) {
            if (!d.isDir() && d.name.equalsIgnoreCase("prototypeenginef.dll")) return rootId;
        }
        for (ChildDoc dir : top) {
            if (!dir.isDir()) continue;
            for (ChildDoc d : listChildren(treeUri, dir.id)) {
                if (!d.isDir() && d.name.equalsIgnoreCase("prototypeenginef.dll")) return dir.id;
            }
        }
        return null;
    }

    /** Only what the port reads: the engine DLL, the archives, and the art/audio/scripts trees. */
    private void collectCopyItems(Uri treeUri, String rootId, List<CopyItem> out) {
        for (ChildDoc d : listChildren(treeUri, rootId)) {
            String lower = d.name.toLowerCase(Locale.US);
            if (d.isDir()) {
                if (lower.equals("art") || lower.equals("audio") || lower.equals("scripts")) {
                    collectRecursive(treeUri, d.id, new File(gameDir, d.name), out);
                }
            } else if (lower.equals("prototypeenginef.dll") || lower.endsWith(".rcf") || lower.endsWith(".p3d")) {
                out.add(new CopyItem(DocumentsContract.buildDocumentUriUsingTree(treeUri, d.id),
                        new File(gameDir, d.name), d.size));
            }
        }
    }

    private void collectRecursive(Uri treeUri, String docId, File destDir, List<CopyItem> out) {
        for (ChildDoc d : listChildren(treeUri, docId)) {
            if (importCancelled) return;
            if (d.isDir()) {
                collectRecursive(treeUri, d.id, new File(destDir, d.name), out);
            } else {
                out.add(new CopyItem(DocumentsContract.buildDocumentUriUsingTree(treeUri, d.id),
                        new File(destDir, d.name), d.size));
            }
        }
    }

    private void startImport(final Uri treeUri) {
        if (importRunning || driverImportRunning) return;
        importRunning = true;
        importCancelled = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        importButton.setEnabled(false);
        play.setEnabled(false);
        importProgress.setVisibility(View.VISIBLE);
        importProgress.setIndeterminate(true);
        importStatus.setVisibility(View.VISIBLE);
        importStatus.setText("Scanning the picked folder...");
        io.execute(new Runnable() {
            @Override
            public void run() {
                runImport(treeUri);
            }
        });
    }

    private void runImport(Uri treeUri) {
        String message;
        try {
            message = doImport(treeUri);
        } catch (RuntimeException e) {
            message = "Import failed: " + e.getMessage();
        }
        final String result = message;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                importRunning = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                importButton.setEnabled(true);
                importProgress.setVisibility(View.GONE);
                importStatus.setText(result);
                Toast.makeText(SetupActivity.this, result, Toast.LENGTH_LONG).show();
                refresh();
            }
        });
    }

    private String doImport(Uri treeUri) {
        String rootId = findGameRoot(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        if (rootId == null) {
            return "No prototypeenginef.dll in the picked folder or its subfolders - "
                    + "pick the game's installation folder.";
        }
        List<CopyItem> items = new ArrayList<>();
        collectCopyItems(treeUri, rootId, items);
        if (items.isEmpty()) return "Nothing to import was found there.";

        long totalBytes = 0;
        for (CopyItem item : items) {
            if (!isUpToDate(item) && item.size > 0) totalBytes += item.size;
        }
        long free = new StatFs(baseDir.getPath()).getAvailableBytes();
        if (totalBytes > free) {
            return String.format(Locale.US, "Not enough free space: the import needs %.1f GB, %.1f GB free.",
                    totalBytes / 1073741824f, free / 1073741824f);
        }

        updateImportProgress(0, totalBytes, 0, items.size(), true);
        long doneBytes = 0;
        int copied = 0, skipped = 0, errors = 0;
        String firstError = null;
        for (CopyItem item : items) {
            if (importCancelled) {
                return "Import cancelled (" + copied + " files copied). "
                        + "Run it again to continue - finished files are skipped.";
            }
            if (isUpToDate(item)) {
                skipped++;
            } else {
                try {
                    doneBytes += copyOne(item, doneBytes, totalBytes);
                    copied++;
                } catch (IOException e) {
                    errors++;
                    if (firstError == null) firstError = item.dest.getName() + ": " + e.getMessage();
                }
            }
            updateImportProgress(doneBytes, totalBytes, copied + skipped, items.size(), false);
        }
        String summary = String.format(Locale.US, "Imported %d files (%.1f GB), %d already up to date.",
                copied, doneBytes / 1073741824f, skipped);
        if (errors > 0) {
            summary += " " + errors + " failed (first: " + firstError + "). Run the import again to retry them.";
        }
        return summary;
    }

    /** A file from an earlier (possibly interrupted) import: same size means it is already copied. */
    private static boolean isUpToDate(CopyItem item) {
        return item.size > 0 && item.dest.isFile() && item.dest.length() == item.size;
    }

    /** Copies one file through a .part sibling so a half-written file never looks complete. */
    private long copyOne(CopyItem item, long doneBefore, long totalBytes) throws IOException {
        File parent = item.dest.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        File part = new File(parent, item.dest.getName() + ".part");
        long done = 0;
        long lastUi = 0;
        try (InputStream in = getContentResolver().openInputStream(item.uri);
             FileOutputStream out = new FileOutputStream(part)) {
            if (in == null) throw new IOException("cannot open the source file");
            byte[] buf = new byte[262144];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (importCancelled) throw new IOException("cancelled");
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastUi >= 250) {
                    lastUi = now;
                    updateImportProgress(doneBefore + done, totalBytes, -1, -1, false);
                }
            }
        } catch (IOException e) {
            part.delete();
            throw e;
        } catch (RuntimeException e) {
            part.delete();
            throw new IOException(String.valueOf(e.getMessage()));
        }
        if (!part.renameTo(item.dest)) {
            part.delete();
            throw new IOException("cannot move into place: " + item.dest.getName());
        }
        return done;
    }

    private void updateImportProgress(final long doneBytes, final long totalBytes,
                                      final int filesDone, final int filesTotal, final boolean scanning) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                importProgress.setIndeterminate(false);
                importProgress.setProgress((int) Math.min(1000, doneBytes * 1000 / Math.max(totalBytes, 1)));
                if (filesDone >= 0) {
                    importStatus.setText(String.format(Locale.US, "%s %.1f / %.1f GB (%d/%d files)",
                            scanning ? "Importing:" : "Importing...",
                            doneBytes / 1073741824f, totalBytes / 1073741824f, filesDone, filesTotal));
                }
            }
        });
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
