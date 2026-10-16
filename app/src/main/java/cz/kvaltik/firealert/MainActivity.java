package cz.kvaltik.firealert;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Environment;
import android.provider.Settings;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

public class MainActivity extends Activity {
    private static final String CHANNEL = "public-news";
    private static final String STORAGE_KEY = "app_state";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView browser;
    private long updateDownloadId = -1;
    private static final String UPDATE_PREF = "pending_update_download";
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()) &&
                intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == updateDownloadId) {
                checkDownloadedApk(true);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(13, 18, 26));
        getWindow().setNavigationBarColor(Color.rgb(13, 18, 26));
        createNotificationChannel();
        updateDownloadId = getPreferences(MODE_PRIVATE).getLong(UPDATE_PREF, -1);
        IntentFilter updateFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, updateFilter, Context.RECEIVER_EXPORTED);
        else registerReceiver(downloadReceiver, updateFilter);
        browser = new WebView(this);
        browser.setBackgroundColor(Color.rgb(13, 18, 26));
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        browser.setWebChromeClient(new WebChromeClient());
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri.toString().startsWith("file:///android_asset/")) return false;
                openExternal(uri.toString());
                return true;
            }
        });
        browser.addJavascriptInterface(new Bridge(), "Android");
        setContentView(browser);
        browser.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onResume() {
        super.onResume();
        // A previously started download may have finished while the app was closed.
        if (browser != null) main.postDelayed(() -> checkDownloadedApk(false), 1200);
    }

    private void checkDownloadedApk(boolean showInstaller) {
        if (updateDownloadId < 0) return;
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(updateDownloadId))) {
            if (c == null || !c.moveToFirst()) return;
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                js("window.onUpdateDownloadStatus('Staženo. Instalaci musíš potvrdit v Androidu.', true)");
                if (showInstaller) installDownloadedApk();
            } else if (status == DownloadManager.STATUS_FAILED) {
                getPreferences(MODE_PRIVATE).edit().remove(UPDATE_PREF).apply();
                updateDownloadId = -1;
                js("window.onUpdateDownloadStatus('Stažení APK selhalo. Zkus to znovu.', false)");
            }
        } catch (Exception e) { js("window.onUpdateDownloadStatus(" + quote("Chyba stahování: " + e.getMessage()) + ", false)"); }
    }

    private void installDownloadedApk() {
        if (updateDownloadId < 0) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Povol instalaci aplikací z tohoto zdroje a pak zvol Instalovat staženou APK.", Toast.LENGTH_LONG).show();
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri apk = dm.getUriForDownloadedFile(updateDownloadId);
            if (apk == null) throw new Exception("APK není k dispozici");
            Intent installer = new Intent(Intent.ACTION_VIEW);
            installer.setDataAndType(apk, "application/vnd.android.package-archive");
            installer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(installer);
        } catch (Exception e) {
            Toast.makeText(this, "Instalátor se nepodařilo spustit: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Veřejné zprávy o zásazích", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Informativní zprávy, nikoli oficiální požární poplach.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private void openExternal(String link) {
        try {
            Uri uri = Uri.parse(link);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("geo"))) return;
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) { Toast.makeText(this, "Odkaz nelze otevřít", Toast.LENGTH_SHORT).show(); }
    }
    private void js(String script) { main.post(() -> { if (browser != null) browser.evaluateJavascript(script, null); }); }
    private static String quote(String text) { return JSONObject.quote(text); }

    class Bridge {
        @JavascriptInterface public String load() {
            return getPreferences(MODE_PRIVATE).getString(STORAGE_KEY, "{}");
        }
        @JavascriptInterface public void save(String json) {
            if (json == null || json.length() > 100_000) return;
            try {
                new JSONObject(json);
                getPreferences(MODE_PRIVATE).edit().putString(STORAGE_KEY, json).apply();
            } catch (Exception ignored) { }
        }
        @JavascriptInterface public void open(String url) { main.post(() -> openExternal(url)); }
        @JavascriptInterface public void requestNotifications() {
            main.post(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
                else Toast.makeText(MainActivity.this, "Oznámení jsou povolena", Toast.LENGTH_SHORT).show();
            });
        }
        @JavascriptInterface public void notifyNew(String title, String link) {
            main.post(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                PendingIntent open = PendingIntent.getActivity(MainActivity.this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                android.app.Notification notification = new android.app.Notification.Builder(MainActivity.this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_fire)
                    .setContentTitle("Nová veřejná zpráva o zásahu")
                    .setContentText(title)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(title + "\nNejde o operační poplach HZS."))
                    .setContentIntent(open)
                    .setAutoCancel(true).build();
                getSystemService(NotificationManager.class).notify((int)(System.currentTimeMillis() % Integer.MAX_VALUE), notification);
            });
        }
        @JavascriptInterface public void installDownloadedUpdate() {
            main.post(() -> checkDownloadedApk(true));
        }
        @JavascriptInterface public void downloadUpdate(String link) {
            main.post(() -> {
                try {
                    Uri url = Uri.parse(link == null ? "" : link);
                    // Only allow official assets from our project's GitHub releases.
                    if (!"https".equals(url.getScheme()) || !"github.com".equalsIgnoreCase(url.getHost()) ||
                        !url.getPath().matches("/Kvaltik/Kvaltik-FIRE-ALERT/releases/download/[^/]+/[^/]+\\.apk"))
                        throw new Exception("Nepovolená adresa APK");
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    DownloadManager.Request request = new DownloadManager.Request(url)
                        .setTitle("Kvaltík FIRE ALERT – aktualizace")
                        .setDescription("Nová verze aplikace se stahuje")
                        .setMimeType("application/vnd.android.package-archive")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverRoaming(false)
                        .setDestinationInExternalFilesDir(MainActivity.this, Environment.DIRECTORY_DOWNLOADS,
                                 "Kvaltik-FIRE-ALERT-update-" + System.currentTimeMillis() + ".apk");
                    updateDownloadId = dm.enqueue(request);
                    getPreferences(MODE_PRIVATE).edit().putLong(UPDATE_PREF, updateDownloadId).apply();
                    js("window.onUpdateDownloadStatus('Stahuji APK…', false)");
                } catch (Exception e) {
                    js("window.onUpdateDownloadStatus(" + quote("Nepodařilo se zahájit stahování: " + e.getMessage()) + ", false)");
                }
            });
        }
        @JavascriptInterface public void checkUpdates() {
            worker.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("https://api.github.com/repos/Kvaltik/Kvaltik-FIRE-ALERT/releases/latest");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "Kvaltik-FIRE-ALERT-Android");
                    if (conn.getResponseCode() == 404) {
                        js("window.onUpdateError('Na GitHubu zatím není vydaná žádná verze. Kontrolu opakuj později.')");
                        return;
                    }
                    if (conn.getResponseCode() != 200) throw new Exception("GitHub HTTP " + conn.getResponseCode());
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    try (InputStream in = conn.getInputStream()) {
                        byte[] bytes = new byte[4096]; int n;
                        while ((n = in.read(bytes)) != -1) {
                            buffer.write(bytes, 0, n);
                            if (buffer.size() > 250000) throw new Exception("Odpověď je příliš velká");
                        }
                    }
                    JSONObject release = new JSONObject(buffer.toString("UTF-8"));
                    String current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    String latest = release.optString("tag_name", "").replaceFirst("^[vV]", "");
                    String apkUrl = "";
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets != null) for (int i=0;i<assets.length();i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset != null && asset.optString("name", "").toLowerCase(Locale.ROOT).endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", ""); break;
                        }
                    }
                    JSONObject result = new JSONObject();
                    result.put("current", current);
                    result.put("latest", latest);
                    result.put("newer", compareVersions(latest, current) > 0);
                    result.put("apkUrl", apkUrl);
                    result.put("notes", release.optString("body", ""));
                    js("window.onUpdateCheck(" + result.toString() + ")");
                } catch (Exception e) {
                    js("window.onUpdateError(" + quote("Kontrola aktualizací selhala: " + e.getMessage()) + ")");
                } finally { if (conn != null) conn.disconnect(); }
            });
        }
        @JavascriptInterface public void getNews(String region) {
            worker.execute(() -> fetchNews(region == null ? "" : region));
        }
    }

    private void fetchNews(String region) {
        HttpURLConnection conn = null;
        try {
            // Jde o veřejně publikované mediální zprávy, nikoliv operační data HZS.
            String subject = region.isEmpty() || region.equals("Celá ČR") ? "hasiči zásah OR požár OR nehoda Česko when:7d" : "hasiči zásah OR požár " + region + " when:7d";
            String address = "https://news.google.com/rss/search?q=" + URLEncoder.encode(subject, "UTF-8") + "&hl=cs&gl=CZ&ceid=CZ:cs";
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "KvaltikFIREALERT/1.0 Android (public news reader)");
            if (conn.getResponseCode() != 200) throw new Exception("Zdroj vrátil HTTP " + conn.getResponseCode());
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            JSONArray entries = new JSONArray();
            try (InputStream input = conn.getInputStream()) {
                Document xml = factory.newDocumentBuilder().parse(input);
                NodeList items = xml.getElementsByTagName("item");
                int n = Math.min(items.getLength(), 40);
                for (int i = 0; i < n; i++) {
                    Element item = (Element) items.item(i);
                    String title = value(item, "title");
                    String link = value(item, "link");
                    String date = value(item, "pubDate");
                    String source = value(item, "source");
                    if (title.isEmpty() || !link.startsWith("https://")) continue;
                    JSONObject story = new JSONObject();
                    story.put("title", title);
                    story.put("url", link);
                    story.put("date", date);
                    story.put("source", source.isEmpty() ? "Veřejné zpravodajství" : source);
                    story.put("id", value(item, "guid").isEmpty() ? link : value(item, "guid"));
                    entries.put(story);
                }
            }
            JSONObject response = new JSONObject();
            response.put("items", entries);
            response.put("region", region);
            response.put("at", System.currentTimeMillis());
            js("window.onNews(" + response + ")");
        } catch (Exception e) {
            js("window.onNewsError(" + quote("Nepodařilo se načíst veřejné zprávy: " + e.getMessage()) + ")");
        } finally { if (conn != null) conn.disconnect(); }
    }
    private static int compareVersions(String a, String b) {
        if (!a.matches("[0-9]+(\\.[0-9]+){0,3}") || !b.matches("[0-9]+(\\.[0-9]+){0,3}")) return 0;
        String[] x=a.split("\\."), y=b.split("\\.");
        for(int i=0;i<4;i++) {
            int n=i<x.length?Integer.parseInt(x[i]):0, m=i<y.length?Integer.parseInt(y[i]):0;
            if(n!=m)return Integer.compare(n,m);
        }
        return 0;
    }
    private static String value(Element item, String tag) {
        NodeList list = item.getElementsByTagName(tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().trim();
    }
    @Override public void onBackPressed() {
        js("window.androidBack()");
    }
    @Override protected void onDestroy() {
        try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        if (browser != null) { browser.removeJavascriptInterface("Android"); browser.destroy(); browser = null; }
        worker.shutdownNow();
        super.onDestroy();
    }
}