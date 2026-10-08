package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class ReceiverNameTest {
    @Test fun enforcesDnsByteBudgetInsteadOfCharacterCount() {
        assertNull(ReceiverName.error("投".repeat(16)))
        assertNotNull(ReceiverName.error("投".repeat(17)))
        assertNull(ReceiverName.error("a".repeat(48)))
        assertNotNull(ReceiverName.error("a".repeat(49)))
    }
    @Test fun rejectsBlankAndDnsEscapesButSupportsChineseAndEmoji() {
        assertNotNull(ReceiverName.error("  "))
        assertNotNull(ReceiverName.error("客厅\n投影"))
        assertNotNull(ReceiverName.error("客厅.投影"))
        assertNull(ReceiverName.error("客厅投影 🎬"))
    }
    @Test fun deviceNameCannotBreakProtocolXml() {
        assertEquals("客厅 &amp; &lt;电视&gt;", ReceiverName.xml("客厅 & <电视>"))
    }
}
