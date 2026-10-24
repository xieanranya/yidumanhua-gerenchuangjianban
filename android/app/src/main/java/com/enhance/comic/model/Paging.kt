package com.enhance.comic.model

/**
 * Expands source pages into display pages. A very wide page (a scanned two-page spread)
 * becomes two half pages so each half can be read at a comfortable size.
 */
fun buildPageRefs(aspects: FloatArray, splitWidePages: Boolean): List<PageRef> {
    if (aspects.isEmpty()) return emptyList()
    val out = ArrayList<PageRef>(aspects.size)
    for (i in aspects.indices) {
        val aspect = aspects[i]
        if (splitWidePages && aspect > 1.55f) {
            out.add(PageRef(i, 1))
            out.add(PageRef(i, 2))
        } else {
            out.add(PageRef(i, 0))
        }
    }
    return out
}

/** Index of the first display page belonging to source page [sourceIndex]. */
fun refIndexOfSource(refs: List<PageRef>, sourceIndex: Int): Int {
    val found = refs.indexOfFirst { it.index == sourceIndex }
    return if (found >= 0) found else 0
}
