package ua.starky.audiokniga.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        CustomSourceEntity::class,
        PlaylistEntity::class,
        PlaylistBookEntity::class,
        BookmarkEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun customSourceDao(): CustomSourceDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Появились свои источники. Библиотеку при этом терять незачем. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_sources` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** Избранное и списки. Библиотеку при этом тоже терять незачем. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlists` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_books` (" +
                        "`playlistId` TEXT NOT NULL, " +
                        "`bookId` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`playlistId`, `bookId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_books_bookId` " +
                        "ON `playlist_books` (`bookId`)"
                )
            }
        }

        /** Порядок глав выбирается для каждой книги отдельно. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `chapterOrder` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Отмеченные моменты в книгах. Полку при этом терять незачем. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                        "`id` TEXT NOT NULL, " +
                        "`bookId` TEXT NOT NULL, " +
                        "`chapterId` TEXT NOT NULL, " +
                        "`chapterTitle` TEXT NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "audiokniga.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also { instance = it }
        }
    }
}
