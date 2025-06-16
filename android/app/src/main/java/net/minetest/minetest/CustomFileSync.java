package net.minetest.minetest;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;

public class CustomFileSync {
    private static final String TAG = "CustomFileSync";
    private static final String REMOTE_URL = "http://10.0.2.2:7007/files/"; // Change as needed
    private static final String LOCAL_ROOT = "/storage/emulated/0/Android/data/net.minetest.minetest/files/Minetest/";

    public void sync() throws Exception {
        final Exception[] thrown = new Exception[1];
        final CountDownLatch latch = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            try {
                syncDir("", REMOTE_URL, LOCAL_ROOT);
            } catch (Exception e) {
                thrown[0] = e;
            } finally {
                latch.countDown();
            }
        });
        t.start();
        latch.await();
        if (thrown[0] != null) throw thrown[0];
    }

    private void syncDir(String relPath, String remoteUrl, String localDir) throws Exception {
        String indexUrl = remoteUrl + (relPath.isEmpty() ? "" : relPath + "/");
        Log.i(TAG, "Fetching index: " + indexUrl);
        JSONArray entries = fetchIndex(indexUrl);
        if (entries == null) return;

        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String name = entry.getString("name");
            boolean isDir = entry.optBoolean("is_dir", false);
            long size = entry.optLong("size", -1);
            long mtime = entry.optLong("mtime", -1);

            String subRelPath = relPath.isEmpty() ? name : relPath + "/" + name;
            String localPath = localDir + (relPath.isEmpty() ? "" : relPath + "/") + name;

            if (isDir) {
                File dir = new File(localPath);
                if (!dir.exists()) dir.mkdirs();
                syncDir(subRelPath, remoteUrl, localDir);
            } else {
                File file = new File(localPath);
                boolean needsDownload = true;
                if (file.exists()) {
                    if (file.length() == size && file.lastModified() / 1000 == mtime) {
                        needsDownload = false;
                    }
                }
                if (needsDownload) {
                    Log.i(TAG, "Downloading: " + subRelPath);
                    downloadFile(remoteUrl + subRelPath, file, mtime);
                }
            }
        }
    }

    private JSONArray fetchIndex(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n));
            in.close();
            conn.disconnect();
            return new JSONArray(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch index: " + urlStr, e);
            return null;
        }
    }

    private void downloadFile(String urlStr, File outFile, long mtime) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            InputStream in = new BufferedInputStream(conn.getInputStream());
            File parent = outFile.getParentFile();
            if (!parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            conn.disconnect();
            if (mtime > 0) outFile.setLastModified(mtime * 1000);
        } catch (Exception e) {
            Log.e(TAG, "Failed to download: " + urlStr, e);
        }
    }
}
