package dev.androml.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ModelRecordEntity::class, ModelFileEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AndroMlDatabase : RoomDatabase() {
    abstract fun modelCatalogDao(): ModelCatalogDao

    companion object {
        fun open(context: Context): AndroMlDatabase = Room.databaseBuilder(
            context.applicationContext,
            AndroMlDatabase::class.java,
            "androml.db",
        ).build()
    }
}
