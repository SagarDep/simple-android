package org.simple.clinic.storage

import android.arch.persistence.room.Insert
import android.arch.persistence.room.OnConflictStrategy
import android.arch.persistence.room.Transaction
import android.arch.persistence.room.Update

/**
 * Mimics sqlite's `upsert` conflict strategy because Room's grammar doesn't understand it.
 */
abstract class DaoWithUpsert<T> {

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  abstract fun insert(record: List<T>): List<Long>

  @Update(onConflict = OnConflictStrategy.FAIL)
  protected abstract fun update(entities: List<T>)

  @Transaction
  protected open fun upsert(records: List<T>) {
    val insertedRowIds = insert(records)
    val recordsToUpdate = ArrayList<T>(records.size)

    for (i in insertedRowIds.indices) {
      if (insertedRowIds[i] == -1L) {
        recordsToUpdate.add(records[i])
      }
    }

    if (recordsToUpdate.isEmpty().not()) {
      update(recordsToUpdate)
    }
  }
}
