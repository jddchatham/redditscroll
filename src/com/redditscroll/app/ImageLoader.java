package com.redditscroll.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dependency-free image loader: memory LRU + disk cache in the app cache dir,
 * background decoding, fade-in on the main thread.
 */
public class ImageLoader {

    private final LinkedHashMap<String, Bitmap> mem;
    private final int memMaxBytes;
    private int memBytes;
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private File diskDir;
    private final Context appContext;

    public ImageLoader(Context c) {
        appContext = c.getApplicationContext();
        memMaxBytes = (int) Math.max(4 * 1024 * 1024L, Runtime.getRuntime().maxMemory() / 8);
        mem = new LinkedHashMap<String, Bitmap>(16, 0.75f, true);
        diskDir = new File(appContext.getCacheDir(), "img");
        if (!diskDir.exists()) diskDir.mkdirs();
    }

    private synchronized Bitmap memGet(String url) {
        return mem.get(url);
    }

    private synchronized void memPut(String url, Bitmap bitmap) {
        Bitmap old = mem.put(url, bitmap);
        if (old != null && !old.isRecycled()) memBytes -= old.getByteCount();
        memBytes += bitmap.getByteCount();
        Iterator<Map.Entry<String, Bitmap>> it = mem.entrySet().iterator();
        while (memBytes > memMaxBytes && it.hasNext()) {
            Map.Entry<String, Bitmap> e = it.next();
            it.remove();
            memBytes -= e.getValue().getByteCount();
        }
    }

    public void load(final ImageView view, final String url) {
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            view.setBackgroundColor(0xFF14181C);
            return;
        }
        Bitmap cached = memGet(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setAlpha(1f);
            return;
        }
        view.setImageDrawable(null);
        view.setBackgroundColor(0xFF14181C);
        view.setTag(url);
        io.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = fromDisk(url);
                if (bmp == null) bmp = fromNet(url);
                if (bmp != null) memPut(url, bmp);
                final Bitmap result = bmp;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Object tag = view.getTag();
                        if (tag instanceof String && tag.equals(url)) {
                            if (result != null) {
                                view.setImageBitmap(result);
                                view.setAlpha(0f);
                                view.animate().alpha(1f).setDuration(220).start();
                            } else {
                                view.setImageDrawable(null);
                                view.setBackgroundColor(0xFF14181C);
                            }
                        }
                    }
                });
            }
        });
    }

    private Bitmap fromDisk(String url) {
        File f = new File(diskDir, key(url));
        if (!f.exists()) return null;
        Bitmap b = decode(f.getAbsolutePath());
        if (b == null) f.delete();
        return b;
    }

    private Bitmap fromNet(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestProperty("User-Agent", Fetch.UA);
            con.setConnectTimeout(12000);
            con.setReadTimeout(15000);
            con.setInstanceFollowRedirects(true);
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) return null;
            File tmp = File.createTempFile("img", ".tmp", diskDir);
            InputStream in = con.getInputStream();
            try {
                OutputStream out = new java.io.FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            Bitmap b = decode(tmp.getAbsolutePath());
            if (b != null) {
                File dest = new File(diskDir, key(url));
                if (dest.exists()) dest.delete();
                tmp.renameTo(dest);
            } else {
                tmp.delete();
            }
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Decode with down-sampling so no bitmap exceeds ~1400px on its long side. */
    private static Bitmap decode(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            FileInputStream fis = new FileInputStream(path);
            try {
                BitmapFactory.decodeStream(fis, null, bounds);
            } finally {
                fis.close();
            }
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (max / (sample * 2) >= 1400) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            fis = new FileInputStream(path);
            try {
                return BitmapFactory.decodeStream(fis, null, opts);
            } finally {
                fis.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String key(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }
}
