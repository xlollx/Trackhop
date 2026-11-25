package com.xlollx.songport.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A name in an inline confirm() stays inside its JavaScript string. */
class HtmlConfirmTest {
    @Test fun anApostropheInANameDoesNotEndTheConfirmString() {
        val b = Html.postButton("/shared", "Leave", "danger", confirm = "Leave Bob's Mix');alert(1);//?")
        // In the attribute: the quote is escaped for JavaScript first, then for HTML.
        assertTrue(b, b.contains("confirm('Leave Bob\\&#39;s Mix\\&#39;);alert(1);//?')"))
        // Plain texts come out as before.
        assertTrue(Html.postButton("/x", "Go", confirm = "Delete it?").contains("onsubmit=\"return confirm('Delete it?')\""))
        assertEquals("a\\'b\\\\c\\x3c/script>", Html.js("a'b\\c</script>"))
    }
}
