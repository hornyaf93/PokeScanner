package com.yourname.pokescanner.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.yourname.pokescanner.data.local.dao.DraftCardDao
import com.yourname.pokescanner.data.local.dao.OwnedCardDao
import com.yourname.pokescanner.data.local.dao.PriceSnapshotDao
import com.yourname.pokescanner.data.local.entity.DraftCardEntity
import com.yourname.pokescanner.data.local.entity.OwnedCardEntity
import com.yourname.pokescanner.data.local.entity.PriceSnapshotEntity

@Database(
    entities = [
        DraftCardEntity::class,
        OwnedCardEntity::class,
        PriceSnapshotEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PokeScannerDatabase : RoomDatabase() {
    abstract fun draftCardDao(): DraftCardDao

    abstract fun ownedCardDao(): OwnedCardDao

    abstract fun priceSnapshotDao(): PriceSnapshotDao
}
