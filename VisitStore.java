package se.besoksloggen;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

final class VisitStore extends SQLiteOpenHelper {
    record WorkDay(String date, int arrival, int departure) {}
    record HomeWork(long start, long end) {}
    record Visit(long start, long end, double lat, double lon, String category,
                 String name, String address, boolean ongoing, String note) {
        String title() {
            if (!name.trim().isEmpty()) return name;
            if (!address.trim().isEmpty()) return address;
            return "Okänd plats";
        }
    }

    record AddressAlert(String id, String name, String address, double lat, double lon,
                        int radius, int minutes, VisitLogic.Dwell dwell) {
        String title() {
            return name.isEmpty() ? address : name;
        }
    }

    private static VisitStore instance;
    private final SharedPreferences settings;

    static synchronized VisitStore get(Context context) {
        if (instance == null) instance = new VisitStore(context.getApplicationContext());
        return instance;
    }

    private VisitStore(Context context) {
        super(context, "besoksloggen.db", null, 3);
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE visits (start INTEGER PRIMARY KEY, finish INTEGER NOT NULL,"
                + " lat REAL NOT NULL, lon REAL NOT NULL, category TEXT NOT NULL, title TEXT NOT NULL,"
                + " address TEXT NOT NULL, ongoing INTEGER NOT NULL, place TEXT NOT NULL, edited INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX visits_finish ON visits(finish)");
        db.execSQL("CREATE TABLE places (id TEXT PRIMARY KEY, category TEXT NOT NULL,"
                + " title TEXT NOT NULL, address TEXT NOT NULL, lat REAL NOT NULL,"
                + " lon REAL NOT NULL, radius INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE tracker (id INTEGER PRIMARY KEY, start INTEGER NOT NULL,"
                + " inside INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, place TEXT NOT NULL,"
                + " category TEXT NOT NULL, title TEXT NOT NULL, address TEXT NOT NULL,"
                + " radius INTEGER NOT NULL, outside INTEGER, out_lat REAL, out_lon REAL, out_acc REAL)");
        createWorkTables(db);
        addVisitNotesAndAlerts(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) addVisitNotesAndAlerts(db);
        if (oldVersion < 3) createWorkTables(db);
    }

    private static void createWorkTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE work_days (date TEXT PRIMARY KEY, arrival INTEGER NOT NULL, departure INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE home_work (start INTEGER PRIMARY KEY, finish INTEGER NOT NULL DEFAULT 0)");
    }

    void saveWorkDay(String date, int arrival, int departure) {
        ContentValues row = new ContentValues(); row.put("date", date);
        row.put("arrival", arrival); row.put("departure", departure);
        getWritableDatabase().replaceOrThrow("work_days", null, row);
    }

    WorkDay workDay(String date) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT date,arrival,departure FROM work_days WHERE date=?", new String[]{date})) {
            return c.moveToFirst() ? new WorkDay(c.getString(0), c.getInt(1), c.getInt(2)) : null;
        }
    }

    List<WorkDay> workDays(String from, String to) {
        List<WorkDay> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT date,arrival,departure FROM work_days WHERE date>=? AND date<? ORDER BY date DESC", new String[]{from,to})) {
            while (c.moveToNext()) rows.add(new WorkDay(c.getString(0), c.getInt(1), c.getInt(2)));
        }
        return rows;
    }

    long homeWorkStarted() { return settings.getLong("home_work_started", 0); }
    void startHomeWork(long at) { settings.edit().putLong("home_work_started", at).apply(); }
    void stopHomeWork(long at) {
        long started = homeWorkStarted();
        if (started > 0 && at > started) {
            ContentValues row = new ContentValues(); row.put("start", started); row.put("finish", at);
            getWritableDatabase().replaceOrThrow("home_work", null, row);
        }
        settings.edit().remove("home_work_started").apply();
    }
    List<HomeWork> homeWork(long from, long to) {
        List<HomeWork> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT start,finish FROM home_work WHERE finish>? AND start<? ORDER BY start DESC", new String[]{Long.toString(from),Long.toString(to)})) {
            while (c.moveToNext()) rows.add(new HomeWork(c.getLong(0), c.getLong(1)));
        }
        long running = homeWorkStarted();
        if (running >= from && running < to) rows.add(0, new HomeWork(running, System.currentTimeMillis()));
        return rows;
    }

    private static void addVisitNotesAndAlerts(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE visits ADD COLUMN note TEXT NOT NULL DEFAULT ''");
        db.execSQL("CREATE TABLE alerts (id TEXT PRIMARY KEY, title TEXT NOT NULL,"
                + " address TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,"
                + " radius INTEGER NOT NULL, minutes INTEGER NOT NULL, entered INTEGER NOT NULL DEFAULT 0,"
                + " inside INTEGER NOT NULL DEFAULT 0, outside INTEGER NOT NULL DEFAULT 0,"
                + " notified INTEGER NOT NULL DEFAULT 0)");
    }

    int visitMinutes() {
        return settings.getInt("visit_minutes", VisitLogic.DEFAULT_VISIT_MINUTES);
    }

    void setVisitMinutes(int minutes) {
        settings.edit().putInt("visit_minutes", minutes).apply();
    }

    List<AddressAlert> alerts() {
        List<AddressAlert> results = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, title, address, lat, lon,"
                + " radius, minutes, entered, inside, outside, notified FROM alerts ORDER BY title, address", null)) {
            while (c.moveToNext()) {
                VisitLogic.Dwell dwell = new VisitLogic.Dwell(c.getLong(7), c.getLong(8),
                        c.getLong(9), c.getInt(10) != 0);
                results.add(new AddressAlert(c.getString(0), c.getString(1), c.getString(2),
                        c.getDouble(3), c.getDouble(4), c.getInt(5), c.getInt(6), dwell));
            }
        }
        return results;
    }

    void putAlert(AddressAlert alert) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues row = dwellValues(alert.dwell());
        row.put("id", alert.id());
        row.put("title", alert.name());
        row.put("address", alert.address());
        row.put("lat", alert.lat());
        row.put("lon", alert.lon());
        row.put("radius", alert.radius());
        row.put("minutes", alert.minutes());
        db.replaceOrThrow("alerts", null, row);
    }

    void removeAlert(String id) {
        getWritableDatabase().delete("alerts", "id=?", new String[]{id});
    }

    void saveDwell(String id, VisitLogic.Dwell dwell) {
        getWritableDatabase().update("alerts", dwellValues(dwell), "id=?", new String[]{id});
    }

    void resetDwells() {
        getWritableDatabase().update("alerts", dwellValues(VisitLogic.Dwell.EMPTY), "entered<>0", null);
    }

    private static ContentValues dwellValues(VisitLogic.Dwell dwell) {
        ContentValues row = new ContentValues();
        row.put("entered", dwell.entered());
        row.put("inside", dwell.inside());
        row.put("outside", dwell.outside());
        row.put("notified", dwell.notified() ? 1 : 0);
        return row;
    }

    boolean enabled() {
        return settings.getBoolean("tracking", false);
    }

    void setEnabled(boolean enabled) {
        settings.edit().putBoolean("tracking", enabled).apply();
    }

    long lastFix() {
        return settings.getLong("last_fix", 0);
    }

    void setLastFix(long at) {
        settings.edit().putLong("last_fix", at).apply();
    }

    List<VisitLogic.Place> places() {
        List<VisitLogic.Place> results = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, category, title, address, lat, lon, radius FROM places ORDER BY id", null)) {
            while (c.moveToNext()) {
                results.add(new VisitLogic.Place(c.getString(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getDouble(4), c.getDouble(5), c.getInt(6)));
            }
        }
        return results;
    }

    void putPlace(VisitLogic.Place place) {
        ContentValues row = new ContentValues();
        row.put("id", place.key());
        row.put("category", place.category());
        row.put("title", place.name());
        row.put("address", place.address());
        row.put("lat", place.lat());
        row.put("lon", place.lon());
        row.put("radius", place.meters());
        getWritableDatabase().replaceOrThrow("places", null, row);
    }

    void removePlace(String key) {
        getWritableDatabase().delete("places", "id=?", new String[]{key});
    }

    VisitLogic.Stop tracker() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT start, inside, lat, lon, place,"
                + " category, title, address, radius, outside, out_lat, out_lon, out_acc"
                + " FROM tracker WHERE id=1", null)) {
            if (!c.moveToFirst()) return null;
            VisitLogic.Fix outside = c.isNull(9) ? null
                    : new VisitLogic.Fix(c.getLong(9), c.getDouble(10), c.getDouble(11), c.getFloat(12));
            return new VisitLogic.Stop(c.getLong(0), c.getLong(1), c.getDouble(2), c.getDouble(3),
                    c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getInt(8), outside);
        }
    }

    void writeTracking(VisitLogic.Stop ended, VisitLogic.Stop active) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int minutes = visitMinutes();
            upsertVisit(db, ended, false, minutes);
            upsertVisit(db, active, true, minutes);
            if (active == null) {
                db.delete("tracker", "id=1", null);
            } else {
                ContentValues row = new ContentValues();
                row.put("id", 1);
                row.put("start", active.start());
                row.put("inside", active.lastInside());
                row.put("lat", active.lat());
                row.put("lon", active.lon());
                row.put("place", active.key());
                row.put("category", active.category());
                row.put("title", active.name());
                row.put("address", active.address());
                row.put("radius", active.radius());
                if (active.outside() != null) {
                    row.put("outside", active.outside().at());
                    row.put("out_lat", active.outside().lat());
                    row.put("out_lon", active.outside().lon());
                    row.put("out_acc", active.outside().accuracy());
                }
                db.replaceOrThrow("tracker", null, row);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void upsertVisit(SQLiteDatabase db, VisitLogic.Stop stop, boolean active, int minutes) {
        if (stop == null) return;
        ContentValues row = new ContentValues();
        row.put("finish", stop.lastInside());
        row.put("ongoing", active ? 1 : 0);
        String[] key = {Long.toString(stop.start())};
        if (db.update("visits", row, "start=?", key) > 0) {
            if (!stop.key().isEmpty()) {
                ContentValues classification = new ContentValues();
                classification.put("place", stop.key());
                classification.put("category", stop.category());
                classification.put("title", stop.name());
                classification.put("lat", stop.lat());
                classification.put("lon", stop.lon());
                if (!stop.address().isEmpty()) classification.put("address", stop.address());
                db.update("visits", classification, "start=? AND place='' AND edited=0", key);
            }
            return;
        }
        if (!stop.qualifies(minutes)) return;
        row.put("start", stop.start());
        row.put("place", stop.key());
        row.put("lat", stop.lat());
        row.put("lon", stop.lon());
        row.put("category", stop.category());
        row.put("title", stop.name());
        row.put("address", stop.address());
        db.insertOrThrow("visits", null, row);
    }

    List<Visit> visits(VisitLogic.Period period) {
        List<Visit> results = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT start, finish, lat, lon,"
                + " category, title, address, ongoing, note FROM visits WHERE finish>? AND start<?"
                + " ORDER BY start DESC", new String[]{Long.toString(period.start()), Long.toString(period.end())})) {
            while (c.moveToNext()) {
                results.add(new Visit(c.getLong(0), c.getLong(1), c.getDouble(2), c.getDouble(3),
                        c.getString(4), c.getString(5), c.getString(6), c.getInt(7) == 1, c.getString(8)));
            }
        }
        return results;
    }

    void editVisit(Visit visit, String category, String name, boolean remember, String note) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues row = new ContentValues();
            row.put("note", note);
            if (!category.equals(visit.category()) || !name.equals(visit.name())) {
                row.put("category", category);
                row.put("title", name);
                row.put("edited", 1);
            }
            db.update("visits", row, "start=?", new String[]{Long.toString(visit.start())});
            if (remember && !VisitLogic.WORK.equals(category) && !VisitLogic.HOME.equals(category)) {
                String key = "poi-" + visit.start();
                for (VisitLogic.Place place : places()) {
                    if (place.key().startsWith("poi-") && VisitLogic.distance(
                            visit.lat(), visit.lon(), place.lat(), place.lon()) <= 30) {
                        key = place.key();
                        break;
                    }
                }
                putPlace(new VisitLogic.Place(key, category, name, visit.address(),
                        visit.lat(), visit.lon(), 50));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void addAddress(long start, String address, String name, String category) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues row = new ContentValues();
        row.put("address", address);
        db.update("visits", row, "start=? AND address=''", new String[]{Long.toString(start)});
        if (!name.trim().isEmpty()) {
            ContentValues venue = new ContentValues();
            venue.put("title", name);
            venue.put("category", category);
            db.update("visits", venue, "start=? AND title='' AND place='' AND edited=0 AND category=?",
                    new String[]{Long.toString(start), VisitLogic.OTHER});
        }
    }
}
