package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavXmlTest {
    @Test
    fun `parses namespaced resources and metadata`() {
        val resources = WebDavXml.parseResources(
            """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/Flip%20Accounting/Pixel+8/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/Flip%20Accounting/Pixel+8/backup_Pixel_8_full_20260829_123456_id.bak</d:href>
                <d:propstat><d:prop>
                  <d:resourcetype/>
                  <d:getcontentlength>12345</d:getcontentlength>
                  <d:getlastmodified>Sat, 29 Aug 2026 12:34:56 GMT</d:getlastmodified>
                </d:prop></d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()
        )

        assertEquals(2, resources.size)
        assertTrue(resources[0].isCollection)
        assertEquals("Pixel+8", resources[0].name)
        assertFalse(resources[1].isCollection)
        assertEquals(12345L, resources[1].contentLength)
        assertEquals("backup_Pixel_8_full_20260829_123456_id.bak", resources[1].name)
    }
}
