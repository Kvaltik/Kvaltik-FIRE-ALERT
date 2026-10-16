package cz.kvaltik.firealert;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
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

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

public class MainActivity extends Activity {
    private static final String CHANNEL = "public-news";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WebView web;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(13,18,26));
        getWindow().setNavigationBarColor(Color.rgb(13,18,26));
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Veřejné zprávy o zásazích", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Pouze veřejné zprávy, nikoliv oficiální poplach HZS.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(13,18,26));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String uri = request.getUrl().toString();
                if (uri.startsWith("file:///android_asset/")) return false;
                openLink(uri);
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void openLink(String url) {
        try {
            Uri u=Uri.parse(url);
            String scheme=u.getScheme();
            if (scheme==null || !(scheme.equals("https")||scheme.equals("http")||scheme.equals("geo"))) return;
            startActivity(new Intent(Intent.ACTION_VIEW,u));
        } catch(Exception e) {
            Toast.makeText(this,"Odkaz nelze otevřít",Toast.LENGTH_SHORT).show();
        }
    }

    private void js(String script) {
        main.post(() -> { if(web!=null) web.evaluateJavascript(script,null); });
    }
    private static String value(Element item,String tag) {
        NodeList nodes=item.getElementsByTagName(tag);
        return nodes.getLength()==0 ? "" : nodes.item(0).getTextContent().trim();
    }

    final class Bridge {
        @JavascriptInterface public String load() {
            return getPreferences(MODE_PRIVATE).getString("app_state","{}");
        }
        @JavascriptInterface public void save(String json) {
            if(json==null||json.length()>100000)return;
            try {
                new JSONObject(json);
                getPreferences(MODE_PRIVATE).edit().putString("app_state",json).apply();
            } catch(Exception ignored) {}
        }
        @JavascriptInterface public void open(String url) {
            main.post(() -> openLink(url));
        }
        @JavascriptInterface public void requestNotifications() {
            main.post(() -> {
                if(Build.VERSION.SDK_INT>=33 &&
                   checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},42);
            });
        }
        @JavascriptInterface public void notifyNew(String title,String url) {
            main.post(() -> {
                if(Build.VERSION.SDK_INT>=33 &&
                   checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
                if(url==null || !url.startsWith("https://"))return;
                Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(url));
                PendingIntent pi=PendingIntent.getActivity(MainActivity.this,0,intent,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                Notification n=new Notification.Builder(MainActivity.this,CHANNEL)
                    .setSmallIcon(R.drawable.ic_fire)
                    .setContentTitle("Nová veřejná zpráva o zásahu")
                    .setContentText(title)
                    .setStyle(new Notification.BigTextStyle().bigText(title+"\\nNejedná se o operační poplach HZS."))
                    .setContentIntent(pi).setAutoCancel(true).build();
                getSystemService(NotificationManager.class).notify((int)(System.currentTimeMillis()%Integer.MAX_VALUE),n);
            });
        }
        @JavascriptInterface public void getNews(String region) {
            worker.execute(() -> fetchNews(region==null?"":region));
        }
    }

    private void fetchNews(String region) {
        HttpURLConnection conn=null;
        try {
            String query=region.isEmpty()||region.equals("Celá ČR")
                ? "hasiči zásah OR požár OR nehoda Česko when:7d"
                : "hasiči zásah OR požár "+region+" when:7d";
            String address="https://news.google.com/rss/search?q="
                +URLEncoder.encode(query,"UTF-8")+"&hl=cs&gl=CZ&ceid=CZ:cs";
            conn=(HttpURLConnection)new URL(address).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent","KvaltikFIREALERT/1.0 Android (public news reader)");
            if(conn.getResponseCode()!=200)throw new Exception("HTTP "+conn.getResponseCode());
            DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            JSONArray items=new JSONArray();
            try(InputStream input=conn.getInputStream()) {
                Document xml=factory.newDocumentBuilder().parse(input);
                NodeList nodes=xml.getElementsByTagName("item");
                for(int i=0;i<Math.min(nodes.getLength(),40);i++) {
                    Element e=(Element)nodes.item(i);
                    String title=value(e,"title"),url=value(e,"link");
                    if(title.isEmpty()||!url.startsWith("https://"))continue;
                    JSONObject x=new JSONObject();
                    x.put("title",title);
                    x.put("url",url);
                    x.put("source",value(e,"source").isEmpty()?"Veřejné zpravodajství":value(e,"source"));
                    x.put("date",value(e,"pubDate"));
                    x.put("id",value(e,"guid").isEmpty()?url:value(e,"guid"));
                    items.put(x);
                }
            }
            JSONObject response=new JSONObject();
            response.put("items",items);
            response.put("region",region);
            response.put("at",System.currentTimeMillis());
            js("window.onNews("+response.toString()+")");
        }catch(Exception e){
            js("window.onNewsError("+JSONObject.quote("Nepodařilo se načíst veřejné zprávy: "+e.getMessage())+")");
        }finally{
            if(conn!=null)conn.disconnect();
        }
    }

    @Override public void onBackPressed() { js("window.androidBack()"); }
    @Override protected void onDestroy() {
        if(web!=null) {
            web.removeJavascriptInterface("Android");
            web.destroy();
            web=null;
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
