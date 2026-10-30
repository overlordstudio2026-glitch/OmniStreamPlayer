package com.overlord.omnistream.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.overlord.omnistream.data.local.dao.PlaybackStateDao
import com.overlord.omnistream.data.local.dao.PlaylistDao
import com.overlord.omnistream.data.local.dao.PlaylistGroupDao
import com.overlord.omnistream.data.local.dao.SubscriptionDao
import com.overlord.omnistream.data.local.entity.PlaybackStateEntity
import com.overlord.omnistream.data.local.entity.PlaylistGroupEntity
import com.overlord.omnistream.data.local.entity.PlaylistItemEntity
import com.overlord.omnistream.data.local.entity.SubscriptionEntity

@Database(
    entities = [PlaylistItemEntity::class, PlaybackStateEntity::class, SubscriptionEntity::class, PlaylistGroupEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun playbackStateDao(): PlaybackStateDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun playlistGroupDao(): PlaylistGroupDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private fun addColumnIfNotExists(db: SupportSQLiteDatabase, table: String, column: String, definition: String) {
            val cursor = db.query("PRAGMA table_info(`$table`)")
            var exists = false
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                if (nameIndex >= 0) {
                    while (it.moveToNext()) {
                        if (it.getString(nameIndex).equals(column, ignoreCase = true)) {
                            exists = true
                            break
                        }
                    }
                }
            }
            if (!exists) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT OR IGNORE INTO `playlist_groups` (`id`, `name`, `createdAt`) VALUES ('default', '預設清單', 0)")
                addColumnIfNotExists(db, "playlist_items", "playlistGroupId", "TEXT NOT NULL DEFAULT 'default'")
                addColumnIfNotExists(db, "subscriptions", "sinceTimestamp", "INTEGER DEFAULT NULL")
                addColumnIfNotExists(db, "subscriptions", "isPlaylist", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "subscriptions", "targetPlaylistGroupId", "TEXT NOT NULL DEFAULT 'default'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("INSERT OR IGNORE INTO `playlist_groups` (`id`, `name`, `createdAt`) VALUES ('default', '預設清單', 0)")
                addColumnIfNotExists(db, "subscriptions", "targetPlaylistGroupId", "TEXT NOT NULL DEFAULT 'default'")
                addColumnIfNotExists(db, "subscriptions", "sinceTimestamp", "INTEGER DEFAULT NULL")
                addColumnIfNotExists(db, "subscriptions", "isPlaylist", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfNotExists(db, "playlist_items", "playlistGroupId", "TEXT NOT NULL DEFAULT 'default'")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfNotExists(db, "playlist_items", "lastPlayedTime", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_1_5 = object : Migration(1, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2.migrate(db)
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
            }
        }

        val MIGRATION_2_5 = object : Migration(2, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
            }
        }

        val MIGRATION_3_5 = object : Migration(3, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4.migrate(db)
                MIGRATION_4_5.migrate(db)
            }
        }

        val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2.migrate(db)
                MIGRATION_2_3.migrate(db)
            }
        }

        val MIGRATION_2_4 = object : Migration(2, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
            }
        }

        val MIGRATION_1_4 = object : Migration(1, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2.migrate(db)
                MIGRATION_2_3.migrate(db)
                MIGRATION_3_4.migrate(db)
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext ?: context
                val instance = Room.databaseBuilder(
                    appContext,
                    AppDatabase::class.java,
                    "omnistream_database.db"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_1_3,
                    MIGRATION_3_4,
                    MIGRATION_2_4,
                    MIGRATION_1_4,
                    MIGRATION_4_5,
                    MIGRATION_3_5,
                    MIGRATION_2_5,
                    MIGRATION_1_5
                )
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
