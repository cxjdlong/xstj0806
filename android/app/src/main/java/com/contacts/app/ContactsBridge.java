package com.contacts.app;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * window.Contacts 桥：
 *   saveXls(name, base64) 把 Excel 写到系统下载目录「Download/通讯录备份」，同步返回真实路径(失败返回 "")；
 *   deleteFile(path)      删除备份文件(MediaStore / 文件路径两套逻辑)；
 *   dial(phone)           调系统拨号盘直接拨打。
 * 前端 native.js 里做能力探测，浏览器/非安卓环境自动降级。
 */
public class ContactsBridge {

    private static final String EXPORT_DIR = "通讯录备份";
    private static final String MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final Context ctx;

    public ContactsBridge(Context c) { this.ctx = c.getApplicationContext(); }

    @JavascriptInterface
    public String saveXls(String name, String base64) {
        try {
            String fname = sanitize(name);
            byte[] data = Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = ctx.getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fname);
                cv.put(MediaStore.Downloads.MIME_TYPE, MIME_XLSX);
                cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + File.separator + EXPORT_DIR);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return "";
                OutputStream os = cr.openOutputStream(uri);
                if (os == null) return "";
                os.write(data);
                os.flush();
                os.close();
                return publicPath(fname);
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        EXPORT_DIR);
                if (!dir.exists() && !dir.mkdirs()) return "";
                File f = new File(dir, fname);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(data);
                fos.flush();
                fos.close();
                return f.getAbsolutePath();
            }
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public boolean deleteFile(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        String p = path.trim();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                String fname = p.substring(p.lastIndexOf('/') + 1);
                String rel = Environment.DIRECTORY_DOWNLOADS + File.separator + EXPORT_DIR + File.separator;
                ContentResolver cr = ctx.getContentResolver();
                Cursor cur = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Downloads._ID},
                        MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?",
                        new String[]{fname, rel}, null);
                if (cur != null) {
                    try {
                        if (cur.moveToFirst()) {
                            long id = cur.getLong(0);
                            Uri u = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id));
                            return cr.delete(u, null, null) > 0;
                        }
                    } finally {
                        cur.close();
                    }
                }
                return false;
            } else {
                File f = new File(p);
                return f.exists() && f.delete();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 直接拨打：调起系统拨号盘并带好号码（用户按一下即呼出） */
    @JavascriptInterface
    public boolean dial(String phone) {
        if (phone == null || phone.trim().isEmpty()) return false;
        try {
            Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.trim()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 公共下载目录下的真实展示路径(用户可在文件管理器里找到) */
    private static String publicPath(String fname) {
        return Environment.getExternalStorageDirectory().getAbsolutePath()
                + File.separator + Environment.DIRECTORY_DOWNLOADS
                + File.separator + EXPORT_DIR
                + File.separator + fname;
    }

    /** 文件名清洗：去掉路径分隔符等非法字符，保留中文与扩展名 */
    private static String sanitize(String name) {
        String n = (name == null || name.trim().isEmpty()) ? "通讯录备份.xlsx" : name.trim();
        n = n.replaceAll("[/\\\\:*?\"<>|]", "_");
        if (!n.toLowerCase().endsWith(".xlsx") && !n.toLowerCase().endsWith(".xls")) n += ".xlsx";
        if (n.length() > 80) n = n.substring(n.length() - 80);
        return n;
    }
}
