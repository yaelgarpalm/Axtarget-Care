package com.example.guardian_iot

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest

/** Keeps legacy files in place and opens separate private files for each subsequent account. */
internal object AccountStorage {
    val lock = Any()
    private const val INDEX = "tzinda_account_storage"
    private fun base(context: Context): Context = if (context is AccountContext) context.baseContext else context.applicationContext

    fun root(context: Context): Context = base(context)

    fun capture(context: Context): Context {
        if (context is AccountContext) return context
        val app = base(context)
        return AccountContext(app, app.getSharedPreferences(INDEX, 0).getString("active_scope", "").orEmpty())
    }

    fun isCurrent(context: Context): Boolean = key(context) == key(base(context))

    fun key(context: Context): String = (capture(context) as AccountContext).scope

    fun activate(context: Context, userId: String): Boolean = synchronized(lock) {
        require(userId.isNotBlank())
        val app = base(context)
        val index = app.getSharedPreferences(INDEX, 0)
        val current = capture(app).getSharedPreferences("tzinda_native", 0)
        val previous = current.getString("local_data_owner_id", "").orEmpty()
        if (previous == userId) return false
        val legacy = app.getSharedPreferences("tzinda_native", 0)
        val legacyOwner = legacy.getString("local_data_owner_id", "").orEmpty()
        val scope = if (legacyOwner.isBlank() || legacyOwner == userId) "" else digest(userId)
        val target = AccountContext(app, scope).getSharedPreferences("tzinda_native", 0)
        // Set ownership before publishing the active pointer. New accounts must not reimport
        // the legacy Flutter/monitor preferences belonging to the first account.
        val edit = target.edit().putString("local_data_owner_id", userId)
        if (scope.isNotBlank()) edit.putBoolean("migration_done", true)
        check(edit.commit()) { "No se pudo preparar el almacenamiento de la cuenta." }
        check(index.edit().putString("active_scope", scope).commit()) { "No se pudo activar la cuenta local." }
        previous.isNotBlank() && previous != userId
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private class AccountContext(base: Context, val scope: String) : ContextWrapper(base) {
        private fun scoped(name: String) = if (scope.isBlank()) name else "${name}_account_$scope"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(scoped(name), mode)
        override fun getDatabasePath(name: String): File = baseContext.getDatabasePath(scoped(name))
        override fun deleteDatabase(name: String): Boolean = baseContext.deleteDatabase(scoped(name))
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            baseContext.openOrCreateDatabase(scoped(name), mode, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            baseContext.openOrCreateDatabase(scoped(name), mode, factory, errorHandler)
    }
}
