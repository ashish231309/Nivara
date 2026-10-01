package com.nivara.app.domain.vault

import java.text.Normalizer

/**
 * Finding a file in the vault by what the vault already knows about it.
 *
 * ### What it searches
 *
 * Only facts the authenticated index already holds: an item's name, the type its provider declared
 * and the kind Nivara classified it as. Nothing is decrypted, nothing is opened and no content is
 * inspected — searching for a document Nivara cannot read works exactly as well as searching for one
 * it can, because the query never reaches the content area at all. That is also why a search result
 * for a file whose encrypted object is missing is still a result: the item is in the index, and
 * opening it produces the same precise failure it produces from the list.
 *
 * ### Why it is plain text matching
 *
 * A query is a substring, matched case-insensitively, after both sides have had their whitespace
 * normalised and their Unicode composed forms unified. There is no fuzzy matching, no scoring, no
 * ranking and therefore no ordering to explain: a result list is the item list, filtered and sorted
 * by the same deterministic order a screen already uses. There is also no history — a query is not
 * stored, counted or reported anywhere, because a record of what somebody searched for is a record of
 * what they own.
 *
 * ### Files that match, and only files
 *
 * An empty or whitespace-only query matches nothing rather than everything: "the user has not asked a
 * question" and "the answer is every file in the vault" are different states, and a screen shows the
 * list for the first one. Callers ask [VaultSearchQuery.isBlank] to tell them apart.
 */
object VaultSearch {

    /**
     * [text] reduced to the form comparisons use: Unicode-composed, case-folded, whitespace-collapsed.
     *
     * Composing first (NFC) is what makes a name typed on one keyboard find a name stored from
     * another: `é` is one character in one form and two in the other, and a person searching for their
     * own file has no way of knowing which one the file system recorded. Folding case with Kotlin's
     * `lowercase` rather than the default locale's rules keeps a Turkish device from finding different
     * files than a German one for the same query.
     */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .lowercase()
            .trim()
            .replace(WHITESPACE_RUN, " ")

    /**
     * Whether [item] matches [query].
     *
     * A match is the normalised query appearing in the normalised name, in the declared type, or in
     * the name of the item's classified kind — so "pdf" finds a document by its type, and "document"
     * finds it by what Nivara calls it, while "jpg" finds a picture either way.
     */
    fun matches(item: VaultItem, query: VaultSearchQuery): Boolean {
        if (query.isBlank) return false
        val needle = query.normalized
        if (matchesText(item.name, needle)) return true
        if (item.mimeType != null && matchesText(item.mimeType, needle)) return true
        return kindWords(VaultContentClassification.kindOf(item.mimeType)).any { word ->
            word.contains(needle)
        }
    }

    /**
     * Whether [album] matches [query]: its title, matched the same way an item's name is.
     *
     * Album membership is deliberately not searched. "Everything in this album matches my query" would
     * make a search result depend on organisation rather than on the files themselves, and a person
     * looking for a file is looking for the file.
     */
    fun matches(album: VaultAlbum, query: VaultSearchQuery): Boolean =
        !query.isBlank && matchesText(album.name, query.normalized)

    /** The items of [items] that match [query], in the order they were given. */
    fun filter(items: List<VaultItem>, query: VaultSearchQuery): List<VaultItem> =
        if (query.isBlank) items else items.filter { item -> matches(item, query) }

    /** The albums of [albums] that match [query], in the order they were given. */
    fun filter(albums: List<VaultAlbum>, query: VaultSearchQuery): List<VaultAlbum> =
        if (query.isBlank) albums else albums.filter { album -> matches(album, query) }

    private fun matchesText(text: String, normalizedNeedle: String): Boolean =
        normalize(text).contains(normalizedNeedle)

    /**
     * The words a kind is found by.
     *
     * The same words the list shows for the kind, so searching for what a row says works — a person
     * who sees "Document" next to a file should be able to type it. Spelled out here rather than taken
     * from the enum's own name so that the searchable word and the drawn label stay one decision.
     */
    private fun kindWords(kind: VaultContentKind): List<String> = when (kind) {
        VaultContentKind.Image -> listOf("image", "picture", "photo")
        VaultContentKind.Video -> listOf("video", "movie")
        VaultContentKind.Audio -> listOf("audio", "sound", "music")
        VaultContentKind.Document -> listOf("document", "doc")
        VaultContentKind.Other -> listOf("other", "file")
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}

/**
 * A question asked of the vault, already reduced to the form comparisons use.
 *
 * The type exists so that "is there a question at all" is answered once, in one place: [isBlank] is
 * the difference between showing the list and showing search results, and a screen that decided that
 * for itself with `query.isEmpty()` would disagree with this class about a query of three spaces.
 */
@JvmInline
value class VaultSearchQuery private constructor(val normalized: String) {

    /** Whether this query asks nothing, so nothing matches it and a list should be shown instead. */
    val isBlank: Boolean
        get() = normalized.isEmpty()

    /** What the person typed as far as this query is concerned — normalised and never raw. */
    override fun toString(): String = "VaultSearchQuery(value=REDACTED)"

    companion object {

        /** An empty query, which matches nothing. */
        val NONE: VaultSearchQuery = VaultSearchQuery("")

        /** The query [text] asks for, normalised. */
        fun of(text: String): VaultSearchQuery = VaultSearchQuery(VaultSearch.normalize(text))
    }
}

/**
 * What a search found, with the states that are not results kept apart.
 *
 * "No file matches" and "the vault's list cannot be read" are different answers, and the second one
 * must never be drawn as the first: a person who is told they have no matching files, when in fact the
 * list could not be read, has been told something untrue about their own vault. There is no separate
 * "unavailable" case for organisation metadata because a search never reads it — albums are not
 * searched, so albums cannot make a search fail.
 */
sealed interface VaultSearchResult {

    /** The query asks nothing. The screen shows the ordinary list. */
    data object NotAsked : VaultSearchResult

    /** A readable index was searched. [items] may be empty, which is a real answer about the vault. */
    data class Found(val items: List<VaultItem>) : VaultSearchResult {

        /** Whether the query was asked and nothing in the vault matches it. */
        val isEmpty: Boolean get() = items.isEmpty()
    }

    /** The index is not in a state that can be searched. Nothing was searched and nothing is claimed. */
    data class CannotSearch(val index: VaultIndexState) : VaultSearchResult
}

/**
 * Searches the vault's index for [query].
 *
 * Pure, so the same index and the same query always produce the same answer, and a test can prove it
 * without a screen. The index state carries through rather than being flattened into an empty result:
 * a vault whose index is unreadable cannot be searched, and that is a different fact from a vault in
 * which nothing matches.
 */
fun VaultIndexState.search(query: VaultSearchQuery): VaultSearchResult = when (this) {
    is VaultIndexState.Ready ->
        if (query.isBlank) VaultSearchResult.NotAsked else VaultSearchResult.Found(VaultSearch.filter(items, query))

    VaultIndexState.Missing ->
        if (query.isBlank) VaultSearchResult.NotAsked else VaultSearchResult.Found(emptyList())

    else -> VaultSearchResult.CannotSearch(this)
}
