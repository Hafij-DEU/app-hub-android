package com.hafij.apphub;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements InstallQueue.Listener {
    /** True while the activity is started; install confirmations open directly then. */
    static volatile boolean visible;

    private static final int MENU_REFRESH = 1, MENU_ADD = 2, MENU_SETTINGS = 3;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private List<AppEntry> apps = new ArrayList<>();
    private boolean checking;
    private LinearLayout content;

    private int cBg, cCard, cText, cSub, cAccent, cGood, cBad;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        cBg = night ? 0xFF111318 : 0xFFF4F5F8;
        cCard = night ? 0xFF1D2027 : 0xFFFFFFFF;
        cText = night ? 0xFFE6E8EE : 0xFF1B1C20;
        cSub = night ? 0xFF9AA0AE : 0xFF5E6270;
        cAccent = night ? 0xFF8AA8FF : 0xFF3D63DD;
        cGood = night ? 0xFF7BD88F : 0xFF1E8E3E;
        cBad = night ? 0xFFFF8A80 : 0xFFC5221F;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(24));
        scroll.addView(content);
        setContentView(scroll);

        apps = Catalog.load(this);
        UpdateJob.schedule(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        InstallQueue.setListener(this);
        for (AppEntry e : apps) Updates.refreshInstalled(this, e);
        render();
        if (!checking && System.currentTimeMillis() - Settings.lastCheck(this) > 10 * 60 * 1000L) refresh();
    }

    @Override
    protected void onStop() {
        visible = false;
        InstallQueue.setListener(null);
        super.onStop();
    }

    @Override
    public void onQueueChanged() {
        for (AppEntry e : apps) Updates.refreshInstalled(this, e);
        render();
    }

    private void refresh() {
        if (checking) return;
        checking = true;
        render();
        bg.execute(() -> {
            List<AppEntry> fresh = Catalog.load(this);
            Updates.checkAll(this, fresh);
            List<AppEntry> pending = new ArrayList<>();
            for (AppEntry e : fresh) if (e.hasUpdate()) pending.add(e);
            Notify.updates(this, pending);
            runOnUiThread(() -> {
                InstallQueue.status.values().removeIf(st -> !st.active);
                apps = fresh;
                checking = false;
                render();
            });
        });
    }

    // ---- UI ---------------------------------------------------------------------------------

    private void render() {
        content.removeAllViews();
        if (!getPackageManager().canRequestPackageInstalls()) content.addView(permissionCard());
        content.addView(banner());
        for (AppEntry e : apps) content.addView(appCard(e));
        TextView foot = text("Updates of apps installed through App Hub go through without a dialog on Android 12+. "
                + "The first install of each app asks once.", 12, cSub);
        foot.setPadding(dp(8), dp(16), dp(8), 0);
        content.addView(foot);
    }

    private View permissionCard() {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        card.addView(text("Allow App Hub to install apps", 16, cText, true));
        TextView t = text("Android asks for this once. Without it App Hub can only download.", 13, cSub);
        t.setPadding(0, dp(4), 0, dp(8));
        card.addView(t);
        Button b = button("Allow", true);
        b.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()))));
        card.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        return card;
    }

    private View banner() {
        List<AppEntry> pending = new ArrayList<>();
        for (AppEntry e : apps) if (e.hasUpdate()) pending.add(e);
        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        String title, sub;
        if (checking) {
            title = "Checking for updates…";
            sub = apps.size() + " apps";
            ProgressBar p = new ProgressBar(this);
            card.addView(p, new LinearLayout.LayoutParams(dp(28), dp(28)));
            texts.setPadding(dp(12), 0, 0, 0);
        } else if (!pending.isEmpty()) {
            title = pending.size() == 1 ? "1 update available" : pending.size() + " updates available";
            StringBuilder names = new StringBuilder();
            for (AppEntry e : pending) names.append(names.length() > 0 ? ", " : "").append(e.name);
            sub = names.toString();
        } else {
            title = "Everything is up to date";
            long last = Settings.lastCheck(this);
            sub = last == 0 ? "" : "Checked " + DateUtils.getRelativeTimeSpanString(last,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        }
        texts.addView(text(title, 18, cText, true));
        if (!sub.isEmpty()) texts.addView(text(sub, 13, cSub));
        card.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!checking && !pending.isEmpty()) {
            Button all = button("Update all", true);
            all.setEnabled(!InstallQueue.busy());
            all.setOnClickListener(v -> updateAll(pending));
            card.addView(all, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        }
        return card;
    }

    private View appCard(AppEntry e) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row);

        row.addView(icon(e), new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(8), 0);
        texts.addView(text(e.name, 16, cText, true));

        InstallQueue.Status st = InstallQueue.status.get(e.id);
        String latest = e.release != null ? e.release.version : null;
        TextView sub;
        if (st != null && st.active) {
            sub = text(st.text, 13, cAccent);
        } else if (st != null && st.error != null) {
            sub = text(st.error, 13, cBad);
        } else if (e.error != null && e.release == null) {
            sub = text(e.installed() ? e.installedVersion + " · " + e.error : e.error, 13, cBad);
        } else if (e.hasUpdate()) {
            sub = text(e.installedVersion + "  →  " + latest, 13, cAccent);
        } else if (e.installed()) {
            sub = text(e.installedVersion + (latest != null ? " · up to date" : ""), 13, cGood);
        } else {
            sub = text(latest != null ? "Not installed · " + latest : "Not installed", 13, cSub);
        }
        texts.addView(sub);
        if (e.stale && st == null) texts.addView(text("Offline: showing last known release", 11, cSub));
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button action = actionButton(e, st);
        if (action != null) {
            row.addView(action, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)));
        }

        if (st != null && st.active) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(st.percent < 0);
            bar.setProgress(Math.max(0, st.percent));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
            lp.topMargin = dp(10);
            card.addView(bar, lp);
        }

        card.setOnClickListener(v -> details(e));
        return card;
    }

    private Button actionButton(AppEntry e, InstallQueue.Status st) {
        if (st != null && st.active) return null;
        if (e.asset != null && (e.hasUpdate() || !e.installed())) {
            Button b = button(e.installed() ? "Update" : "Install", true);
            b.setOnClickListener(v -> install(e));
            return b;
        }
        if (e.installed() && !e.self) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(e.pkg);
            if (launch == null) return null;
            Button b = button("Open", false);
            b.setOnClickListener(v -> startActivity(launch));
            return b;
        }
        return null;
    }

    private View icon(AppEntry e) {
        Drawable d = null;
        if (e.installed()) {
            try {
                d = getPackageManager().getApplicationIcon(e.pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (d == null && e.icon != null) {
            int res = getResources().getIdentifier(e.icon, "drawable", getPackageName());
            if (res != 0) d = getDrawable(res);
        }
        if (d != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(d);
            return iv;
        }
        TextView letter = text(e.name.substring(0, 1).toUpperCase(Locale.ROOT), 20, Color.WHITE, true);
        letter.setGravity(Gravity.CENTER);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setShape(GradientDrawable.OVAL);
        float[] hsv = {Math.abs(e.id.hashCode()) % 360, 0.55f, 0.75f};
        bgd.setColor(Color.HSVToColor(hsv));
        letter.setBackground(bgd);
        return letter;
    }

    // ---- actions ----------------------------------------------------------------------------

    private boolean canInstall() {
        if (getPackageManager().canRequestPackageInstalls()) return true;
        Toast.makeText(this, "Allow App Hub to install apps first", Toast.LENGTH_LONG).show();
        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())));
        return false;
    }

    private void install(AppEntry e) {
        if (!canInstall()) return;
        InstallQueue.enqueue(this, e);
        render();
    }

    private void updateAll(List<AppEntry> pending) {
        if (!canInstall()) return;
        AppEntry self = null;
        for (AppEntry e : pending) {
            if (e.self) self = e;
            else InstallQueue.enqueue(this, e);
        }
        if (self != null) InstallQueue.enqueue(this, self); // last: it restarts App Hub
        render();
    }

    private void details(AppEntry e) {
        StringBuilder msg = new StringBuilder();
        msg.append(e.repo).append('\n');
        if (e.pkg != null) msg.append(e.pkg).append('\n');
        if (e.installed()) msg.append("Installed: ").append(e.installedVersion).append('\n');
        if (e.release != null) {
            msg.append("Latest: ").append(e.release.tag);
            if (e.release.published.length() >= 10) msg.append(" (").append(e.release.published, 0, 10).append(')');
            msg.append('\n');
            if (e.asset != null) {
                msg.append(e.asset.name).append(" · ").append(Formatter.formatShortFileSize(this, e.asset.size)).append('\n');
            }
            if (!e.release.body.trim().isEmpty()) msg.append('\n').append(e.release.body.trim());
        } else if (e.error != null) {
            msg.append('\n').append(e.error);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(e.release != null && !e.release.name.isEmpty() ? e.release.name : e.name)
                .setMessage(msg.toString())
                .setPositiveButton("GitHub", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(e.release != null ? e.release.htmlUrl : "https://github.com/" + e.repo))));
        if (e.installed() && !e.self) {
            b.setNeutralButton("Uninstall", (d, w) -> startActivity(
                    new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg))));
        }
        if (e.custom) {
            b.setNegativeButton("Remove from list", (d, w) -> {
                apps.remove(e);
                saveCustom();
                render();
            });
        }
        b.show();
    }

    private void addApp() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText repo = new EditText(this);
        repo.setHint("owner/repo or GitHub link");
        repo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText name = new EditText(this);
        name.setHint("Name (optional)");
        CheckBox pre = new CheckBox(this);
        pre.setText("Include pre-releases");
        box.addView(repo);
        box.addView(name);
        box.addView(pre);
        new AlertDialog.Builder(this)
                .setTitle("Add app")
                .setMessage("Any GitHub repo whose releases carry an .apk.")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String r = Catalog.parseRepo(repo.getText().toString());
                    if (r == null) {
                        Toast.makeText(this, "Not a GitHub repo", Toast.LENGTH_LONG).show();
                        return;
                    }
                    for (AppEntry x : apps) {
                        if (x.repo.equalsIgnoreCase(r)) {
                            Toast.makeText(this, "Already in the list", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    AppEntry e = new AppEntry();
                    e.repo = r;
                    e.id = "custom-" + r.toLowerCase(Locale.ROOT).replace('/', '-');
                    String n = name.getText().toString().trim();
                    e.name = n.isEmpty() ? r.substring(r.indexOf('/') + 1) : n;
                    e.prerelease = pre.isChecked();
                    e.custom = true;
                    apps.add(e);
                    saveCustom();
                    bg.execute(() -> {
                        Updates.check(this, e);
                        runOnUiThread(this::render);
                    });
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveCustom() {
        try {
            Catalog.saveCustom(this, apps);
        } catch (java.io.IOException ex) {
            Toast.makeText(this, "Could not save: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void settings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        CheckBox auto = new CheckBox(this);
        auto.setText("Install updates automatically in the background (Wi-Fi only, Android 12+)");
        auto.setChecked(Settings.autoInstall(this));
        CheckBox pre = new CheckBox(this);
        pre.setText("Include pre-releases for every app");
        pre.setChecked(Settings.includePrerelease(this));
        EditText token = new EditText(this);
        token.setHint("GitHub token (optional)");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(Settings.token(this));
        TextView note = text("A token only raises GitHub's limit of 60 checks per hour. "
                + "A fine-grained token with no permissions is enough.", 12, cSub);
        TextView ver = text("App Hub " + BuildConfig.VERSION_NAME, 12, cSub);
        ver.setPadding(0, dp(12), 0, 0);
        box.addView(auto);
        box.addView(pre);
        box.addView(token);
        box.addView(note);
        box.addView(ver);
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Settings.prefs(this).edit()
                            .putBoolean("auto_install", auto.isChecked())
                            .putBoolean("prerelease", pre.isChecked())
                            .putString("token", token.getText().toString().trim())
                            .apply();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_REFRESH, 0, "Check now").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, MENU_ADD, 1, "Add app");
        menu.add(0, MENU_SETTINGS, 2, "Settings");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_REFRESH:
                refresh();
                return true;
            case MENU_ADD:
                addApp();
                return true;
            case MENU_SETTINGS:
                settings();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // ---- view helpers -----------------------------------------------------------------------

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(cCard);
        g.setCornerRadius(dp(16));
        card.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    private Button button(String label, boolean filled) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setMinWidth(dp(84));
        b.setMinimumWidth(dp(84));
        b.setStateListAnimator(null);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        if (filled) {
            g.setColor(cAccent);
            b.setTextColor(cCard == 0xFFFFFFFF ? Color.WHITE : 0xFF111318);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(1), cSub);
            b.setTextColor(cAccent);
        }
        b.setBackground(g);
        return b;
    }

    private TextView text(String s, int sp, int color) {
        return text(s, sp, color, false);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
