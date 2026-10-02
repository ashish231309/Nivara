package com.nivara.app.data.vault.viewer

import android.content.Context
import com.nivara.app.domain.vault.VaultContentReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Creates a media engine for one viewer.
 *
 * The engine holds a borrow of the vault's key for as long as a viewer is open, so it cannot be a
 * singleton: a factory makes one per viewer, and the viewer releases it. This is also the seam the
 * JVM suites use — a recording fake of the interface proves the viewer stops playback, releases the
 * engine and never refreshes a session, without a platform player anywhere near the test.
 */
internal interface VaultMediaEngineFactory {

    /** Creates an engine whose background work runs in [scope]. */
    fun create(scope: CoroutineScope): VaultMediaEngine
}

/** Creates a document engine for one viewer, for the same reason and with the same seam. */
internal interface VaultDocumentEngineFactory {

    /** Creates an engine whose background work runs in [scope]. */
    fun create(scope: CoroutineScope): VaultDocumentEngine
}

/** The platform's media engine, built from the vault's content reader. */
internal class NivaraMediaEngineFactory(
    private val reader: VaultContentReader,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : VaultMediaEngineFactory {

    override fun create(scope: CoroutineScope): VaultMediaEngine =
        NivaraVaultMediaEngine(reader = reader, scope = scope, dispatcher = dispatcher)
}

/** The platform's document engine, built from the vault's content reader. */
internal class NivaraDocumentEngineFactory(
    private val context: Context,
    private val reader: VaultContentReader,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VaultDocumentEngineFactory {

    override fun create(scope: CoroutineScope): VaultDocumentEngine =
        NivaraVaultDocumentEngine(
            context = context,
            reader = reader,
            scope = scope,
            dispatcher = dispatcher,
        )
}
