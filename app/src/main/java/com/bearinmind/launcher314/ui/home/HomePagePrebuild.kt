package com.bearinmind.launcher314.ui.home

/** Issue #115: keep every home page built (a 1-page window still built a page on each swipe's first frame); looped pages are capped so no page is built twice. The Compose 1.5.4 pager ignores later changes to this, so it is fixed from the start. */
internal fun homePrebuildCount(totalPages: Int, loop: Boolean): Int =
    if (loop && totalPages >= 2) ((totalPages - 2) / 2).coerceAtLeast(0)
    else (totalPages - 1).coerceAtLeast(0)
