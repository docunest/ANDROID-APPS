package com.docunest.dock

import android.content.Context
import android.widget.Toast

object Actions {
    /** "I like this place" — one tap, no typing. Named by MapPal when there's signal. */
    fun bookmarkHere(context: Context) {
        Toast.makeText(context, "★ Saving this spot…", Toast.LENGTH_SHORT).show()
        Locator.current(context) { loc ->
            if (loc == null) {
                Toast.makeText(context, "No GPS fix — not saved. Try again in a moment.", Toast.LENGTH_LONG).show()
            } else {
                Sync.bookmark(context, loc)
                Toast.makeText(context, "★ Saved — it's in your places", Toast.LENGTH_LONG).show()
            }
        }
    }
}
