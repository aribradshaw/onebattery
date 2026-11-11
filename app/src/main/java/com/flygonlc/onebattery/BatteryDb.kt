package com.flygonlc.onebattery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class BatteryDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "onebattery.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE samples (
                time INTEGER PRIMARY KEY,
                level INTEGER NOT NULL,
                status INTEGER NOT NULL,
                plugged INTEGER NOT NULL,
                temp REAL NOT NULL,
                voltage INTEGER NOT NULL,
                current_ma INTEGER,
                charge_mah INTEGER,
                cycles INTEGER
            )"""
        )
        createMeasurements(db)
        createDrain(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createMeasurements(db)
        if (oldVersion < 3) createDrain(db)
    }

    private fun createDrain(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE app_drain (
                hour INTEGER NOT NULL,
                package TEXT NOT NULL,
                mah REAL NOT NULL,
                ms INTEGER NOT NULL,
                PRIMARY KEY (hour, package)
            )"""
        )
    }

    fun addDrain(entries: List<DrainEntry>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (e in entries) {
                db.execSQL(
                    """INSERT INTO app_drain (hour, package, mah, ms) VALUES (?, ?, ?, ?)
                       ON CONFLICT (hour, package) DO UPDATE SET mah = mah + excluded.mah, ms = ms + excluded.ms""",
                    arrayOf<Any>(e.hour, e.pkg, e.mah, e.ms)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun drainTotals(from: Long, to: Long): List<DrainTotal> {
        val out = ArrayList<DrainTotal>()
        readableDatabase.rawQuery(
            "SELECT package, SUM(mah), SUM(ms) FROM app_drain WHERE hour >= ? AND hour < ? GROUP BY package",
            arrayOf((from / DrainTracker.HOUR_MS).toString(), (to / DrainTracker.HOUR_MS + 1).toString())
        ).use { c ->
            while (c.moveToNext()) out += DrainTotal(c.getString(0), c.getDouble(1), c.getLong(2))
        }
        return out
    }

    /** mAh per local calendar day for one app, oldest first, dated at noon. */
    fun drainByDay(pkg: String, from: Long): List<Pair<Long, Float>> {
        val days = java.util.TreeMap<Long, Float>()
        val cal = java.util.Calendar.getInstance()
        readableDatabase.rawQuery(
            "SELECT hour, mah FROM app_drain WHERE package = ? AND hour >= ?",
            arrayOf(pkg, (from / DrainTracker.HOUR_MS).toString())
        ).use { c ->
            while (c.moveToNext()) {
                cal.timeInMillis = c.getLong(0) * DrainTracker.HOUR_MS
                cal.set(java.util.Calendar.HOUR_OF_DAY, 12)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                days[cal.timeInMillis] = (days[cal.timeInMillis] ?: 0f) + c.getFloat(1)
            }
        }
        return days.map { it.key to it.value }
    }

    private fun createMeasurements(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE measurements (
                time INTEGER PRIMARY KEY,
                charging INTEGER NOT NULL,
                from_level INTEGER NOT NULL,
                to_level INTEGER NOT NULL,
                mah INTEGER NOT NULL,
                capacity_mah INTEGER NOT NULL
            )"""
        )
    }

    fun insertMeasurement(m: Measurement) {
        val v = ContentValues().apply {
            put("time", m.time)
            put("charging", if (m.charging) 1 else 0)
            put("from_level", m.fromLevel)
            put("to_level", m.toLevel)
            put("mah", m.mah)
            put("capacity_mah", m.capacityMah)
        }
        writableDatabase.insertWithOnConflict("measurements", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun measurements(): List<Measurement> {
        val out = ArrayList<Measurement>()
        readableDatabase.rawQuery(
            "SELECT time, charging, from_level, to_level, mah, capacity_mah FROM measurements ORDER BY time", null
        ).use { c ->
            while (c.moveToNext()) {
                out += Measurement(c.getLong(0), c.getInt(1) == 1, c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5))
            }
        }
        return out
    }

    fun insert(s: BatterySnapshot) {
        val v = ContentValues().apply {
            put("time", s.time)
            put("level", s.level)
            put("status", s.status)
            put("plugged", s.plugged)
            put("temp", s.tempC)
            put("voltage", s.voltageMv)
            put("current_ma", s.currentMa)
            put("charge_mah", s.chargeMah)
            put("cycles", s.cycleCount)
        }
        writableDatabase.insertWithOnConflict("samples", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun lastSampleTime(): Long =
        readableDatabase.rawQuery("SELECT MAX(time) FROM samples", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }

    fun since(from: Long): List<BatterySnapshot> {
        val out = ArrayList<BatterySnapshot>()
        readableDatabase.rawQuery(
            "SELECT time, level, status, plugged, temp, voltage, current_ma, charge_mah, cycles FROM samples WHERE time >= ? ORDER BY time",
            arrayOf(from.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += BatterySnapshot(
                    time = c.getLong(0),
                    level = c.getInt(1),
                    status = c.getInt(2),
                    plugged = c.getInt(3),
                    health = 0,
                    tempC = c.getFloat(4),
                    voltageMv = c.getInt(5),
                    currentMa = if (c.isNull(6)) null else c.getInt(6),
                    chargeMah = if (c.isNull(7)) null else c.getInt(7),
                    cycleCount = if (c.isNull(8)) null else c.getInt(8),
                    technology = null,
                    chargeTimeRemainingMs = null,
                )
            }
        }
        return out
    }

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null).use {
        it.moveToFirst(); it.getLong(0)
    }

    fun prune(olderThan: Long) {
        writableDatabase.delete("samples", "time < ?", arrayOf(olderThan.toString()))
        writableDatabase.delete("app_drain", "hour < ?", arrayOf((olderThan / DrainTracker.HOUR_MS).toString()))
    }

    companion object {
        @Volatile private var instance: BatteryDb? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: BatteryDb(context.applicationContext).also { instance = it }
        }
    }
}
