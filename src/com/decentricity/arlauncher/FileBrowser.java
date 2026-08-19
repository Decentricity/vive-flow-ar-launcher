package com.decentricity.arlauncher;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** Text-only directory listing. Lands on shared Android storage home. */
final class FileBrowser {
    static final int KIND_HOME = 1;
    static final int KIND_UP = 2;
    static final int KIND_DIR = 3;
    static final int KIND_FILE = 4;

    static final class Row {
        final String name;
        final int kind;
        final File target;

        Row(String name, int kind, File target) {
            this.name = name;
            this.kind = kind;
            this.target = target;
        }

        boolean navigable() {
            return kind != KIND_FILE;
        }
    }

    private File cwd;
    private String error = "";
    private final ArrayList<Row> rows = new ArrayList<Row>();

    FileBrowser(Context context) {
        // Context kept on the constructor so MainActivity can construct after onCreate.
    }

    File cwd() {
        return cwd;
    }

    String error() {
        return error;
    }

    ArrayList<Row> rows() {
        return rows;
    }

    String pathLabel() {
        if (cwd == null) return "";
        return cwd.getAbsolutePath();
    }

    File homeDir() {
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) return ext;
        return new File("/storage/emulated/0");
    }

    void goHome() {
        open(homeDir());
    }

    void ensureListed() {
        if (cwd == null) goHome();
    }

    void open(File dir) {
        File next = dir != null ? dir : homeDir();
        if (next == null) next = new File("/storage/emulated/0");
        try {
            next = next.getCanonicalFile();
        } catch (Exception ignored) {
            next = next.getAbsoluteFile();
        }
        cwd = next;
        rows.clear();
        error = "";
        rows.add(new Row("~", KIND_HOME, homeDir()));
        File parent = cwd.getParentFile();
        if (parent != null) {
            rows.add(new Row("..", KIND_UP, parent));
        }
        File[] kids = cwd.listFiles();
        if (kids == null) {
            if (!cwd.exists()) error = "missing";
            else if (!cwd.canRead()) error = "no permission";
            else error = "unreadable";
            return;
        }
        Arrays.sort(kids, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                boolean ad = a.isDirectory();
                boolean bd = b.isDirectory();
                if (ad != bd) return ad ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (int i = 0; i < kids.length; i++) {
            File f = kids[i];
            String name = f.getName();
            if (name.equals(".") || name.equals("..")) continue;
            rows.add(new Row(name, f.isDirectory() ? KIND_DIR : KIND_FILE, f));
        }
    }

    boolean activate(int index) {
        if (index < 0 || index >= rows.size()) return false;
        Row row = rows.get(index);
        if (!row.navigable() || row.target == null) return false;
        open(row.target);
        return true;
    }

    static String ellipsize(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        if (max <= 1) return "…";
        return "…" + s.substring(s.length() - (max - 1));
    }
}
