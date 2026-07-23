package io.rebble.libpebblecommon.sailfish.contacts

import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.contacts.SystemContact
import io.rebble.libpebblecommon.contacts.SystemContacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/** Contacts from the qtcontacts-sqlite database (see [QtContactsDb]). */
internal class SailfishSystemContacts : SystemContacts {
    private val logger = Logger.withTag("SailfishSystemContacts")

    override fun registerForContactsChanges(): Flow<Unit> = flow {
        // No change-notification API without QtContacts; watch the db file mtime instead.
        var last = QtContactsDb.lastModified()
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL)
            val now = QtContactsDb.lastModified()
            if (now != last) {
                last = now
                logger.d { "contacts db changed" }
                emit(Unit)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getContacts(): List<SystemContact> = withContext(Dispatchers.IO) {
        QtContactsDb.getContacts()
    }

    override fun hasPermission(): Boolean = QtContactsDb.isAvailable()

    override suspend fun getContactImage(lookupKey: String): ImageBitmap? = null

    private companion object {
        private val POLL_INTERVAL = 60.seconds
    }
}
