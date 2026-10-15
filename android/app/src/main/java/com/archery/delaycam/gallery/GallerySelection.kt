package com.archery.delaycam.gallery

/** URI identity survives reorder/refresh; newly saved clips are never implicitly selected. */
data class GallerySelection(val active: Boolean = false, val ids: Set<String> = emptySet()) {
    fun toggle(id: String) = GallerySelection(true, if (id in ids) ids - id else ids + id)
    fun toggleAll(available: List<String>): GallerySelection {
        val all = available.toSet()
        return GallerySelection(true, if (all.isNotEmpty() && ids.containsAll(all)) emptySet() else all)
    }
    fun reconcile(available: List<String>) = copy(ids = ids.intersect(available.toSet()))
}
