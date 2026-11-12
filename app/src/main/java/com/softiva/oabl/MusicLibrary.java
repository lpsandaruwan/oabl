package com.softiva.oabl;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.media.MediaMetadataRetriever;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Private, durable catalogue. SAF access is read-only; the user's files are never changed. */
final class MusicLibrary extends SQLiteOpenHelper {
    static final class Track {
        long id, duration, plays;
        String uri, root, title, artist, album, folder, name, art;
        boolean favorite;
        String subtitle() { return artist + " · " + (album.isEmpty() ? folder : album); }
        /** The artist, or for untagged songs the album/folder, so cards never read "Unknown artist". */
        String byline() {
            if (artist != null && !artist.isEmpty() && !artist.startsWith("Unknown")) return artist;
            return album != null && !album.isEmpty() ? album : folder;
        }
    }
    static final class Playlist {
        long id; String name;
        Playlist(long id, String name) { this.id = id; this.name = name; }
    }
    static final class BrowseGroup {
        String key, root;
        int songs, albums;
        Track artwork;
    }
    static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static MusicLibrary instance;
    static synchronized MusicLibrary get(Context context) {
        if (instance == null) instance = new MusicLibrary(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Runnable> observers = new HashSet<>();
    volatile boolean scanning;
    volatile boolean cancelScan;
    volatile String scanStatus = "Choose a music folder to begin";
    private MusicLibrary(Context context) { super(context, "music.db", null, 4); this.context = context; }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tracks (_id INTEGER PRIMARY KEY,identity TEXT UNIQUE,uri TEXT UNIQUE NOT NULL,root TEXT NOT NULL,title TEXT,artist TEXT,album TEXT,folder TEXT,name TEXT,duration INTEGER,art TEXT,stamp TEXT,seen INTEGER,favorite INTEGER DEFAULT 0,plays INTEGER DEFAULT 0,played_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX track_artist ON tracks(artist COLLATE NOCASE)");
        db.execSQL("CREATE TABLE playlists (_id INTEGER PRIMARY KEY,name TEXT UNIQUE COLLATE NOCASE NOT NULL)");
        db.execSQL("CREATE TABLE members (playlist INTEGER,track INTEGER,position INTEGER,PRIMARY KEY(playlist,track))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion<2){
            db.execSQL("ALTER TABLE tracks ADD COLUMN identity TEXT");
            try(Cursor c=db.rawQuery("SELECT _id,uri FROM tracks",null)){
                while(c.moveToNext()){ContentValues v=new ContentValues();try{v.put("identity",identity(Uri.parse(c.getString(1))));}catch(RuntimeException ignored){continue;}db.update("tracks",v,"_id=?",new String[]{""+c.getLong(0)});}
            }
            db.execSQL("CREATE INDEX track_identity ON tracks(identity)");
        }
        if(oldVersion<3){
            db.execSQL("ALTER TABLE tracks ADD COLUMN played_at INTEGER DEFAULT 0");
            // Untagged songs take their folder's name as the album (Artist/Album folder layouts).
            try(Cursor c=db.rawQuery("SELECT _id,album,folder FROM tracks",null)){
                while(c.moveToNext()){
                    String album=folderAlbum(c.getString(1),c.getString(2));
                    if(album.equals(c.getString(1)))continue;
                    ContentValues v=new ContentValues();v.put("album",album);db.update("tracks",v,"_id=?",new String[]{""+c.getLong(0)});
                }
            }
        }
        if(oldVersion<4){
            // Playback moved to the platform decoders (FLAC, MP3, AAC, Vorbis, Opus, PCM): formats
            // the box can't decode, and playlist files indexed as songs, leave the library.
            StringBuilder where=new StringBuilder();
            for(String ext:DROPPED){if(where.length()>0)where.append(" OR ");where.append("lower(name) LIKE '%.").append(ext).append("'");}
            db.execSQL("DELETE FROM tracks WHERE "+where);
            db.execSQL("DELETE FROM members WHERE track NOT IN (SELECT _id FROM tracks)");
        }
    }
    private static boolean untagged(String album){
        if(album==null)return true;String a=album.trim().toLowerCase(Locale.ROOT);
        return a.isEmpty()||a.equals("none")||a.equals("unknown")||a.equals("<unknown>")||a.equals("unknown album");
    }
    /** The album tag, or for untagged songs the name of the folder they sit in (none at the card root). */
    static String folderAlbum(String album,String folder){
        if(!untagged(album))return album.trim();
        if(folder==null||folder.isEmpty()||folder.equals("Music"))return "";
        int slash=folder.lastIndexOf('/');return folder.substring(slash+1).trim();
    }
    private static String identity(Uri uri){return uri.getAuthority()+"|"+DocumentsContract.getDocumentId(uri);}
    void observe(Runnable action) { synchronized (observers) { observers.add(action); } }
    void unobserve(Runnable action) { synchronized (observers) { observers.remove(action); } }
    void changed() { main.post(() -> { synchronized (observers) { for (Runnable r : observers) r.run(); } }); }

    List<Track> query(String search, String sort, String collection, long playlist) {
        return query(search, sort, collection, playlist, null, null);
    }
    List<Track> query(String search, String sort, String collection, long playlist, String browseKey, String browseRoot) {
        String where = "1=1";
        ArrayList<String> args = new ArrayList<>();
        if (browseKey != null && "Artists".equals(collection)) {
            where += " AND t.artist=? COLLATE NOCASE"; args.add(browseKey);
        } else if (browseKey != null && "Albums".equals(collection)) {
            where += " AND t.album=? COLLATE NOCASE"; args.add(browseKey);
        } else if (browseKey != null && "Folders".equals(collection)) {
            // Relative names can repeat on different cards. A folder belongs to its selected root.
            where += " AND t.root=? AND t.folder=?"; args.add(browseRoot); args.add(browseKey);
        }
        if ("Stars".equals(collection)) where += " AND t.favorite=1";
        if ("Frequent".equals(collection)) where += " AND t.plays>=3";
        if ("Recent".equals(collection)) where += " AND t.played_at>0";
        if (playlist > 0) { where += " AND EXISTS(SELECT 1 FROM members m WHERE m.track=t._id AND m.playlist=?)"; args.add("" + playlist); }
        if (search != null && !search.trim().isEmpty()) {
            // Escape SQL LIKE metacharacters so filenames containing %/_ are searchable literally.
            String term = like(search);
            where += " AND (t.title LIKE ? ESCAPE '\\' OR t.artist LIKE ? ESCAPE '\\' OR t.album LIKE ? ESCAPE '\\' OR t.folder LIKE ? ESCAPE '\\' OR t.name LIKE ? ESCAPE '\\')";
            for (int i = 0; i < 5; i++) args.add(term);
        }
        String order = "Recent".equals(collection) ? "played_at DESC LIMIT 60" : "Artist".equals(sort) ? "artist COLLATE NOCASE,album COLLATE NOCASE,name COLLATE NOCASE"
                : "Folder".equals(sort) ? "folder COLLATE NOCASE,name COLLATE NOCASE"
                : "Filename".equals(sort) ? "name COLLATE NOCASE"
                : "Random".equals(sort) ? "RANDOM()"
                : "Most played".equals(sort) ? "plays DESC,title COLLATE NOCASE" : "title COLLATE NOCASE";
        ArrayList<Track> tracks = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT t.* FROM tracks t WHERE " + where + " ORDER BY " + order, args.toArray(new String[0]))) {
            while (c.moveToNext()) tracks.add(read(c));
        }
        return tracks;
    }
    /** One query for groups and representative artwork; never guess artist names from paths. */
    List<BrowseGroup> groups(String collection, String search) {
        boolean artists = "Artists".equals(collection);
        boolean albums = "Albums".equals(collection);
        String field = artists ? "artist" : albums ? "album" : "folder";
        String grouping = artists ? "artist COLLATE NOCASE" : albums ? "album COLLATE NOCASE" : "root,folder";
        // Untagged songs use their folder name as the album; songs at the card root have none.
        String where = albums ? " WHERE COALESCE(album,'')<>'' AND LOWER(TRIM(album)) NOT IN ('none','unknown','<unknown>','unknown album')" : "";
        if (search != null && !search.trim().isEmpty()) {
            where += (where.isEmpty() ? " WHERE " : " AND ") + field + " LIKE ? ESCAPE '\\'";
        }
        String[] args = search == null || search.trim().isEmpty() ? null : new String[]{like(search)};
        ArrayList<BrowseGroup> result = new ArrayList<>();
        String sql = "SELECT t.*,g.songs,g.albums FROM tracks t JOIN (SELECT "
                + "COALESCE(MIN(CASE WHEN COALESCE(art,'')<>'' THEN _id END),MIN(_id)) AS representative,"
                + "COUNT(*) AS songs,COUNT(DISTINCT CASE WHEN LOWER(TRIM(COALESCE(album,''))) NOT IN ('','none','unknown','<unknown>','unknown album') THEN album END COLLATE NOCASE) AS albums FROM tracks"
                + where + " GROUP BY " + grouping + ") g ON t._id=g.representative ORDER BY t." + field + " COLLATE NOCASE,t.root";
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                BrowseGroup group = new BrowseGroup(); group.artwork = read(c);
                group.key = artists ? group.artwork.artist : albums ? group.artwork.album : group.artwork.folder;
                group.root = group.artwork.root;
                group.songs = c.getInt(c.getColumnIndexOrThrow("songs"));
                group.albums = c.getInt(c.getColumnIndexOrThrow("albums")); result.add(group);
            }
        }
        return result;
    }
    private static String like(String search) {
        return "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
    Track track(long id) {
        try (Cursor c = getReadableDatabase().query("tracks", null, "_id=?", new String[]{"" + id}, null, null, null)) {
            return c.moveToFirst() ? read(c) : null;
        }
    }
    private static Track read(Cursor c) {
        Track t = new Track();
        t.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        t.duration = c.getLong(c.getColumnIndexOrThrow("duration"));
        t.plays = c.getLong(c.getColumnIndexOrThrow("plays"));
        t.favorite = c.getInt(c.getColumnIndexOrThrow("favorite")) != 0;
        t.uri = value(c, "uri"); t.root = value(c, "root"); t.title = value(c, "title"); t.artist = value(c, "artist");
        t.album = value(c, "album"); t.folder = value(c, "folder"); t.name = value(c, "name"); t.art = value(c, "art");
        return t;
    }
    private static String value(Cursor c, String key) { String s = c.getString(c.getColumnIndexOrThrow(key)); return s == null ? "" : s; }
    void favorite(long id, boolean value) {
        ContentValues v = new ContentValues(); v.put("favorite", value ? 1 : 0);
        getWritableDatabase().update("tracks", v, "_id=?", new String[]{"" + id}); changed();
    }
    void played(long id) { getWritableDatabase().execSQL("UPDATE tracks SET plays=plays+1,played_at=? WHERE _id=?", new Object[]{System.currentTimeMillis(), id}); changed(); }
    List<Playlist> playlists() {
        ArrayList<Playlist> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT _id,name FROM playlists ORDER BY name COLLATE NOCASE", null)) {
            while (c.moveToNext()) result.add(new Playlist(c.getLong(0), c.getString(1)));
        }
        return result;
    }
    long createPlaylist(String name) {
        name = name.trim();
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("Use a playlist name between 1 and 80 characters");
        ContentValues v = new ContentValues(); v.put("name", name);
        long id = getWritableDatabase().insertWithOnConflict("playlists", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (id < 0) try (Cursor c = getReadableDatabase().rawQuery("SELECT _id FROM playlists WHERE name=? COLLATE NOCASE", new String[]{name})) { if (c.moveToFirst()) id = c.getLong(0); }
        changed(); return id;
    }
    void add(long playlist, long track) {
        getWritableDatabase().execSQL("INSERT OR IGNORE INTO members(playlist,track,position) SELECT ?,?,COALESCE(MAX(position),0)+1 FROM members WHERE playlist=?", new Object[]{playlist, track, playlist}); changed();
    }
    /** A playlist's songs in their saved order. */
    List<Track> playlistTracks(long playlist) {
        ArrayList<Track> tracks = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT t.* FROM tracks t JOIN members m ON m.track=t._id WHERE m.playlist=? ORDER BY m.position",
                new String[]{"" + playlist})) {
            while (c.moveToNext()) tracks.add(read(c));
        }
        return tracks;
    }
    /** Songs per playlist, for pickers. */
    java.util.Map<Long, Integer> playlistSizes() {
        java.util.HashMap<Long, Integer> sizes = new java.util.HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT playlist,COUNT(*) FROM members GROUP BY playlist", null)) {
            while (c.moveToNext()) sizes.put(c.getLong(0), c.getInt(1));
        }
        return sizes;
    }
    /**
     * Applies a playlist file ({"playlists":[{"name","tracks":[{"uri"}]}], "replace":true}) in one
     * transaction. Songs are matched by URI; ones not in the library are skipped. With replace, a
     * playlist of the same name gets exactly the file's songs in the file's order; other playlists
     * are never touched. Returns the number of playlists written.
     */
    int importPlaylists(org.json.JSONObject root) throws org.json.JSONException {
        org.json.JSONArray lists = root.getJSONArray("playlists");
        boolean replace = root.optBoolean("replace", true);
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        int written = 0;
        try {
            for (int i = 0; i < lists.length(); i++) {
                org.json.JSONObject list = lists.getJSONObject(i);
                String name = list.getString("name").trim();
                if (name.isEmpty() || name.length() > 80) continue;
                ContentValues v = new ContentValues(); v.put("name", name);
                long id = db.insertWithOnConflict("playlists", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                if (id < 0) try (Cursor c = db.rawQuery("SELECT _id FROM playlists WHERE name=? COLLATE NOCASE", new String[]{name})) { if (c.moveToFirst()) id = c.getLong(0); }
                if (id < 0) continue;
                int position = 0;
                if (replace) db.delete("members", "playlist=?", new String[]{"" + id});
                else try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(position),0) FROM members WHERE playlist=?", new String[]{"" + id})) { if (c.moveToFirst()) position = c.getInt(0); }
                org.json.JSONArray songs = list.getJSONArray("tracks");
                for (int j = 0; j < songs.length(); j++) {
                    try (Cursor c = db.rawQuery("SELECT _id FROM tracks WHERE uri=?", new String[]{songs.getJSONObject(j).getString("uri")})) {
                        if (c.moveToFirst()) db.execSQL("INSERT OR IGNORE INTO members(playlist,track,position) VALUES(?,?,?)", new Object[]{id, c.getLong(0), ++position});
                    }
                }
                written++;
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        changed();
        return written;
    }
    void remove(long playlist, long track) { getWritableDatabase().delete("members", "playlist=? AND track=?", new String[]{""+playlist,""+track}); changed(); }
    void deletePlaylist(long id) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { db.delete("members", "playlist=?", new String[]{""+id}); db.delete("playlists", "_id=?", new String[]{""+id}); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); } changed();
    }
    /**
     * Files that aren't playable songs: formats the box has no decoder for (APE, AIFF, ALAC, WMA,
     * WavPack, MP2, DSD), and playlist files, whose audio/x-mpegurl type once let them in.
     */
    private static final String[] DROPPED = {"ape", "aiff", "aif", "alac", "wma", "wv", "mp2", "dsf", "dff", "m3u", "m3u8", "pls"};
    private static final Set<String> PLAYABLE_TYPES = new HashSet<>(java.util.Arrays.asList(
            "audio/flac", "audio/x-flac", "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/ogg",
            "audio/opus", "audio/wav", "audio/x-wav", "audio/vnd.wave", "audio/x-matroska"));
    /** Songs Media3 can play with the box's decoders: FLAC, MP3, AAC (.m4a), Ogg Vorbis, Opus, WAV, Matroska audio. */
    static boolean audio(String name, String mime) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(flac|mp3|m4a|aac|ogg|oga|opus|wav|wave|mka)$")) return true;
        for (String ext : DROPPED) if (lower.endsWith("." + ext)) return false;
        return mime != null && PLAYABLE_TYPES.contains(mime.toLowerCase(Locale.ROOT));
    }
    synchronized void scan(Uri root) {
        if (scanning) return;
        scanning = true; cancelScan = false; scanStatus = "Reading folders…"; changed();
        WORK.execute(() -> {
            try {
                long generation = System.currentTimeMillis();
                Set<String> visited = new HashSet<>(); int[] count = {0}, moved = {0};
                // Set when another music folder was chosen in place of this one: see scanDirectory.
                String moveFrom = context.getSharedPreferences("music", 0).getString("move_from", null);
                if (root.toString().equals(moveFrom)) moveFrom = null;
                requireRoot(root);
                scanDirectory(root, DocumentsContract.getTreeDocumentId(root), "", generation, visited, count, 0, moveFrom, moved);
                requireRoot(root);
                if(cancelScan)throw new java.util.concurrent.CancellationException();
                // A complete scan alone may prune stale files; failed/unmounted scans retain the old catalogue.
                SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
                try {
                    db.delete("tracks", "root=? AND seen<>?", new String[]{root.toString(), ""+generation});
                    db.execSQL("DELETE FROM members WHERE track NOT IN (SELECT _id FROM tracks)");
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                context.getSharedPreferences("music", 0).edit().putString("tree", root.toString()).remove("move_from").apply();
                scanStatus = count[0] + " tracks indexed" + (moved[0] > 0 ? " · " + moved[0] + " moved with their stars and playlists" : " · read-only library");
            } catch (Exception error) { android.util.Log.w("OablMusic", "Library scan incomplete: " + error.getClass().getSimpleName()); scanStatus = cancelScan ? "Scan stopped · existing library kept" : "Scan incomplete · reconnect the card or reselect the folder. Existing library kept."; }
            finally { scanning = false; changed(); }
        });
    }
    private void requireRoot(Uri root) {
        try(Cursor c=context.getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(root,DocumentsContract.getTreeDocumentId(root)),new String[]{"document_id"},null,null,null)){
            if(c==null||!c.moveToFirst())throw new IllegalStateException("Card unavailable");
        }
    }
    /**
     * Indexes one folder and its subfolders. With {@code moveFrom} (the music folder used before,
     * say the memory card's, when the phone's copy has been chosen instead), a song new to the
     * library takes over the old folder's song at the same place (same subfolder and file name):
     * the row moves to the new file and keeps its id, so its star, play count, playlists and place
     * in the queue carry over. The same file (same size) keeps its tags without being read again.
     */
    private void scanDirectory(Uri root, String id, String path, long generation,
                               Set<String> visited, int[] count, int depth, String moveFrom, int[] moved) throws Exception {
        if (depth > 64) throw new IllegalStateException("Folder nesting limit");
        if (!visited.add(id)) return;
        ArrayList<String[]> rows = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(root, id),
                new String[]{"document_id", "_display_name", "mime_type", "last_modified", "_size"}, null, null, null)) {
            if (c == null) throw new IllegalStateException("Card unavailable");
            while (c.moveToNext()) rows.add(new String[]{c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)});
        }
        String cover = "";
        for (String[] row : rows) if (row[1] != null && row[1].toLowerCase(Locale.ROOT).matches("(cover|folder|front|album)\\.(jpg|jpeg|png|webp)")) {
            cover = DocumentsContract.buildDocumentUriUsingTree(root, row[0]).toString(); break;
        }
        for (String[] row : rows) {
            if(cancelScan)throw new java.util.concurrent.CancellationException();
            String name = row[1] == null ? "Unknown" : row[1];
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(row[2])) {
                scanDirectory(root, row[0], path.isEmpty() ? name : path + "/" + name, generation, visited, count, depth+1, moveFrom, moved);
            } else if (audio(name, row[2])) {
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(root, row[0]);
                String identity=identity(uri);
                String stamp = "v2:" + row[3] + ":" + row[4];
                ContentValues v = new ContentValues(); v.put("seen", generation);
                boolean same = false, known = false;
                try (Cursor c = getReadableDatabase().rawQuery("SELECT stamp,duration FROM tracks WHERE identity=?", new String[]{identity})) { known = c.moveToFirst(); same = known && stamp.equals(c.getString(0)) && c.getLong(1)>0; }
                v.put("uri",uri.toString());v.put("root",root.toString());v.put("identity",identity);
                // A cached file may now be reached through a different selected subtree.
                v.put("name",name);v.put("folder",path.isEmpty()?"Music":path);
                long movedRow = -1;
                if (!known && moveFrom != null) {
                    try (Cursor c = getReadableDatabase().rawQuery("SELECT _id,stamp,duration FROM tracks WHERE root=? AND folder=? AND name=?", new String[]{moveFrom, v.getAsString("folder"), name})) {
                        if (c.moveToFirst()) {
                            movedRow = c.getLong(0); moved[0]++;
                            String old = c.getString(1);
                            if (old != null && old.endsWith(":" + row[4]) && c.getLong(2) > 0) { same = true; v.put("stamp", stamp); }
                        }
                    }
                }
                if (same && movedRow >= 0) { v.put("art", cover); getWritableDatabase().update("tracks", v, "_id=?", new String[]{""+movedRow}); }
                else if (same) { if(!cover.isEmpty())v.put("art",cover); getWritableDatabase().update("tracks", v, "identity=?", new String[]{identity}); }
                else {
                    v.put("uri", uri.toString()); v.put("root", root.toString()); v.put("name", name);
                    v.put("title", name.replaceFirst("\\.[^.]+$", "")); v.put("artist", "Unknown artist");
                    v.put("album", ""); v.put("folder", path.isEmpty() ? "Music" : path); v.put("duration", 0); v.put("art", cover); v.put("stamp", stamp);
                    try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                        if (fd == null) throw new IllegalStateException("File unavailable");
                        // Android's own tag reader; embedded covers are read later by MusicArt.
                        MediaMetadataRetriever reader = new MediaMetadataRetriever();
                        try {
                            reader.setDataSource(fd.getFileDescriptor());
                            putMeta(v, "title", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
                            String artist = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                            if (artist == null || artist.trim().isEmpty()) artist = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
                            putMeta(v, "artist", artist);
                            putMeta(v, "album", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
                            String length = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                            try { v.put("duration", Math.max(0, Long.parseLong(length == null ? "0" : length.trim()))); } catch (NumberFormatException ignored) { v.put("duration", 0); }
                        } finally { reader.release(); }
                    } catch (java.io.IOException | RuntimeException unreadable) {
                        // A corrupt/unsupported file remains visible by filename; playback reports its error.
                        v.putNull("stamp");
                    }
                    v.put("album", folderAlbum(v.getAsString("album"), v.getAsString("folder")));
                    if (movedRow >= 0) getWritableDatabase().update("tracks", v, "_id=?", new String[]{""+movedRow});
                    else if (getWritableDatabase().update("tracks", v, "identity=?", new String[]{identity}) == 0) getWritableDatabase().insertOrThrow("tracks", null, v);
                }
                count[0]++;
                if (count[0] % 20 == 0) { scanStatus = "Indexing · " + count[0] + " tracks"; changed(); }
            }
        }
    }
    private static void putMeta(ContentValues v, String field, String text) { if (text != null && !text.trim().isEmpty()) v.put(field, text.trim()); }
}
