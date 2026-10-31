package com.smartstudy;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.SyncFailedException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads and writes files in a folder the user authorised through the Storage Access Framework,
 * normally Documents/smartstudy. Files there survive uninstalling the app; after reinstalling,
 * the user picks the same folder again. See docs/decisions/002-public-documents-storage-via-saf.md.
 *
 * Paths from JavaScript are relative to the smartstudy folder and use "/".
 */
@CapacitorPlugin(name = "SafFolder")
public class SafFolderPlugin extends Plugin {
    private static final String PREFS = "saf_folder";
    private static final String KEY_TREE = "treeUri";
    private static final String KEY_BASE = "baseDocumentId";
    private static final String FOLDER_NAME = "smartstudy";
    private static final String EXTERNAL_STORAGE = "com.android.externalstorage.documents";
    // octet-stream keeps the exact display name; a JSON MIME type may make providers append ".json".
    private static final String FILE_MIME = "application/octet-stream";

    /**
     * File calls run on the plugin thread, choosing a folder finishes on the main thread. A file call checks its folder
     * and does all its work under this lock, so it never starts in one folder and continues in the next.
     */
    private final Object folderLock = new Object();

    /** The folder a file call was checked against; the call's work uses it, never the preferences again. */
    private static final class Target {
        final Uri tree;
        final String base;

        Target(Uri tree, String base) {
            this.tree = tree;
            this.base = base;
        }
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private ContentResolver resolver() {
        return getContext().getContentResolver();
    }

    // ---------------------------------------------------------------- folder selection

    @PluginMethod
    public void getFolder(PluginCall call) {
        String tree = prefs().getString(KEY_TREE, null);
        String base = prefs().getString(KEY_BASE, null);
        JSObject result = new JSObject();
        if (tree == null || base == null) {
            result.put("configured", false);
            call.resolve(result);
            return;
        }
        Uri treeUri = Uri.parse(tree);
        boolean granted = false;
        for (UriPermission permission : resolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(treeUri) && permission.isReadPermission() && permission.isWritePermission()) {
                granted = true;
                break;
            }
        }
        boolean exists = false;
        if (granted) {
            try (Cursor cursor = resolver().query(DocumentsContract.buildDocumentUriUsingTree(treeUri, base),
                    new String[]{Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                exists = cursor != null && cursor.moveToFirst();
            } catch (Exception ignored) {
                exists = false;
            }
        }
        result.put("configured", true);
        result.put("granted", granted && exists);
        result.put("label", labelOf(base));
        result.put("folderId", tree + "#" + base);
        call.resolve(result);
    }

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:Documents"));
        }
        startActivityForResult(call, intent, "onFolderPicked");
    }

    @ActivityCallback
    private void onFolderPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            call.reject("没有选择文件夹", "CANCELLED");
            return;
        }
        Uri treeUri = data.getData();
        try {
            resolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            String baseId;
            if (FOLDER_NAME.equals(displayName(treeUri, treeId))) {
                baseId = treeId;
            } else {
                // The user picked a parent such as Documents: use or create its smartstudy subfolder.
                baseId = findChild(treeUri, treeId, FOLDER_NAME);
                if (baseId == null) {
                    Uri created = DocumentsContract.createDocument(resolver(),
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId), Document.MIME_TYPE_DIR, FOLDER_NAME);
                    if (created == null) {
                        call.reject("无法在所选文件夹中创建 smartstudy 文件夹，请先在文件管理里新建 Documents/smartstudy，再选择它", "CREATE_FAILED");
                        return;
                    }
                    baseId = DocumentsContract.getDocumentId(created);
                    if (!FOLDER_NAME.equals(displayName(treeUri, baseId))) {
                        call.reject("系统把新文件夹命名成了 " + displayName(treeUri, baseId) + "，请在文件管理里检查后直接选择 smartstudy 文件夹", "CREATE_FAILED");
                        return;
                    }
                }
            }
            String previous;
            synchronized (folderLock) {
                previous = prefs().getString(KEY_TREE, null);
                prefs().edit().putString(KEY_TREE, treeUri.toString()).putString(KEY_BASE, baseId).apply();
            }
            if (previous != null && !previous.equals(treeUri.toString())) releaseQuietly(Uri.parse(previous));

            JSObject folder = new JSObject();
            folder.put("configured", true);
            folder.put("granted", true);
            folder.put("label", labelOf(baseId));
            folder.put("folderId", treeUri.toString() + "#" + baseId);
            call.resolve(folder);
        } catch (Exception e) {
            call.reject("授权文件夹失败：" + e.getMessage(), "PICK_FAILED", e);
        }
    }

    // ---------------------------------------------------------------- file operations

    @PluginMethod
    public void readText(PluginCall call) {
        synchronized (folderLock) {
            readTextLocked(call);
        }
    }

    private void readTextLocked(PluginCall call) {
        try {
            Target target = requireFolder(call);
            Uri uri = resolve(target, requirePath(call), false, false);
            JSObject result = new JSObject();
            if (uri == null) {
                result.put("text", JSObject.NULL);
            } else {
                try (InputStream input = resolver().openInputStream(uri)) {
                    if (input == null) throw new FileNotFoundException(uri.toString());
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(chunk)) != -1) buffer.write(chunk, 0, read);
                    // Strict decoding: bytes that are not UTF-8 must not turn into U+FFFD, or saving
                    // the text back would silently change the file.
                    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
                    try {
                        result.put("text", decoder.decode(ByteBuffer.wrap(buffer.toByteArray())).toString());
                    } catch (CharacterCodingException e) {
                        call.reject("文件不是 UTF-8 文本", "NOT_UTF8", e);
                        return;
                    }
                }
            }
            call.resolve(result);
        } catch (Exception e) {
            fail(call, "读取失败", e);
        }
    }

    @PluginMethod
    public void writeText(PluginCall call) {
        synchronized (folderLock) {
            writeTextLocked(call);
        }
    }

    private void writeTextLocked(PluginCall call) {
        try {
            Target target = requireFolder(call);
            String text = call.getString("text");
            if (text == null) throw new IllegalArgumentException("缺少 text");
            Uri uri = resolve(target, requirePath(call), true, false);
            // Write through a file descriptor and sync it: the caller deletes the old version right after
            // this returns, so the new bytes must be on disk, not only in the page cache, if power is lost.
            try (ParcelFileDescriptor descriptor = resolver().openFileDescriptor(uri, "wt")) {
                if (descriptor == null) throw new FileNotFoundException(uri.toString());
                try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
                    output.write(text.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    try {
                        output.getFD().sync();
                    } catch (SyncFailedException ignored) {
                        // Some document providers do not support sync; the data has still been written.
                    }
                }
            }
            call.resolve();
        } catch (Exception e) {
            fail(call, "写入失败", e);
        }
    }

    @PluginMethod
    public void list(PluginCall call) {
        synchronized (folderLock) {
            listLocked(call);
        }
    }

    private void listLocked(PluginCall call) {
        try {
            Target target = requireFolder(call);
            String path = call.getString("path", "");
            JSArray entries = new JSArray();
            String dirId = path == null || path.isEmpty() ? target.base : idOf(resolve(target, path, false, true));
            if (dirId != null) {
                Uri tree = target.tree;
                try (Cursor cursor = resolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId),
                        new String[]{Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE}, null, null, null)) {
                    while (cursor != null && cursor.moveToNext()) {
                        JSObject entry = new JSObject();
                        entry.put("name", cursor.getString(0));
                        entry.put("isDirectory", Document.MIME_TYPE_DIR.equals(cursor.getString(1)));
                        entries.put(entry);
                    }
                }
            }
            JSObject result = new JSObject();
            result.put("entries", entries);
            call.resolve(result);
        } catch (Exception e) {
            fail(call, "列出文件失败", e);
        }
    }

    @PluginMethod
    public void remove(PluginCall call) {
        synchronized (folderLock) {
            removeLocked(call);
        }
    }

    private void removeLocked(PluginCall call) {
        try {
            Target target = requireFolder(call);
            Uri uri = resolve(target, requirePath(call), false, false);
            if (uri != null) DocumentsContract.deleteDocument(resolver(), uri);
            call.resolve();
        } catch (Exception e) {
            fail(call, "删除失败", e);
        }
    }

    @PluginMethod
    public void rename(PluginCall call) {
        synchronized (folderLock) {
            renameLocked(call);
        }
    }

    private void renameLocked(PluginCall call) {
        try {
            Target target = requireFolder(call);
            String path = requirePath(call);
            String newName = call.getString("newName");
            if (newName == null || newName.isEmpty() || newName.contains("/")) throw new IllegalArgumentException("新文件名无效");
            Uri uri = resolve(target, path, false, false);
            if (uri == null) throw new FileNotFoundException(path);
            int slash = path.lastIndexOf('/');
            String parentId = slash < 0 ? target.base : idOf(resolve(target, path.substring(0, slash), false, true));
            if (findChild(target.tree, parentId, newName) != null) throw new IllegalStateException("目标已存在：" + newName);
            Uri renamed = DocumentsContract.renameDocument(resolver(), uri, newName);
            String actual = displayName(target.tree, renamed == null ? idOf(uri) : idOf(renamed));
            if (!newName.equals(actual)) throw new IllegalStateException("系统把文件命名成了 " + actual);
            call.resolve();
        } catch (Exception e) {
            fail(call, "重命名失败", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Every file call names the folder it was opened for (folderId from getFolder/pickFolder). After the user chose
     * another folder, a call still running for the old one is refused instead of reading or writing the new folder.
     */
    private Target requireFolder(PluginCall call) {
        String expected = call.getString("folderId");
        String tree = prefs().getString(KEY_TREE, null);
        String base = prefs().getString(KEY_BASE, null);
        if (tree == null || base == null) throw new IllegalStateException("还没有授权数据文件夹");
        if (expected == null || !expected.equals(tree + "#" + base)) throw new IllegalStateException("数据文件夹已经更换");
        return new Target(Uri.parse(tree), base);
    }

    private static String requirePath(PluginCall call) {
        String path = call.getString("path");
        if (path == null || path.isEmpty() || path.startsWith("/")) throw new IllegalArgumentException("路径无效：" + path);
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("路径无效：" + path);
        }
        return path;
    }

    /**
     * Walk a relative path from the smartstudy folder. Returns null when something is missing and
     * create is false. With create, missing directories are created, and the last segment becomes
     * a directory (isDirectory) or an empty file.
     */
    private Uri resolve(Target target, String path, boolean create, boolean isDirectory) throws FileNotFoundException {
        Uri tree = target.tree;
        String current = target.base;
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            boolean last = i == parts.length - 1;
            String child = findChild(tree, current, parts[i]);
            if (child == null) {
                if (!create) return null;
                String mime = last && !isDirectory ? FILE_MIME : Document.MIME_TYPE_DIR;
                Uri created = DocumentsContract.createDocument(resolver(), DocumentsContract.buildDocumentUriUsingTree(tree, current), mime, parts[i]);
                if (created == null) throw new FileNotFoundException("无法创建 " + parts[i]);
                child = DocumentsContract.getDocumentId(created);
                String actual = displayName(tree, child);
                if (!parts[i].equals(actual)) throw new IllegalStateException("系统把 " + parts[i] + " 命名成了 " + actual);
            }
            current = child;
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, current);
    }

    private String findChild(Uri tree, String parentId, String name) {
        try (Cursor cursor = resolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
                new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) return cursor.getString(0);
            }
        }
        return null;
    }

    private String displayName(Uri tree, String documentId) {
        try (Cursor cursor = resolver().query(DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
                new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static String idOf(Uri uri) {
        return uri == null ? null : DocumentsContract.getDocumentId(uri);
    }

    /** "primary:Documents/smartstudy" -> "Documents/smartstudy" */
    private static String labelOf(String documentId) {
        int colon = documentId.indexOf(':');
        return colon >= 0 ? documentId.substring(colon + 1) : documentId;
    }

    private void releaseQuietly(Uri uri) {
        try {
            resolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
            // The old grant may already be gone.
        }
    }

    private static void fail(PluginCall call, String what, Exception e) {
        String code = e instanceof SecurityException ? "PERMISSION" : e instanceof FileNotFoundException ? "NOT_FOUND" : "IO";
        call.reject(what + "：" + e.getMessage(), code, e);
    }
}
