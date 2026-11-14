package app.moneytracker.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.moneytracker.db.Photo as PhotoRow
import app.moneytracker.domain.Photo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Which photo files belong to which entry. The files themselves live on the platform side. */
class PhotoStore(private val db: Db) {
    private val q get() = db.q

    fun photos(txnId: String): Flow<List<Photo>> = q.photosForTxn(txnId).asFlow().mapToList(db.io).map { rows ->
        rows.map { Photo(it.id, it.txnId, it.fileName, it.createdAt) }
    }
    /** Synchronous: the edit screen fills its form from this before the first frame. */
    fun photosNow(txnId: String): List<Photo> = q.photosForTxn(txnId).executeAsList().map { Photo(it.id, it.txnId, it.fileName, it.createdAt) }
    val txnIdsWithPhotos: CachedFlow<Set<String>> = db.cached({ q.txnIdsWithPhotos() }) { it.toSet() }
    fun addPhoto(txnId: String, fileName: String) = q.insertPhoto(PhotoRow(newId(), txnId, fileName, db.now(), 0))
    fun deletePhoto(id: String) = q.softDeletePhoto(id)
}
