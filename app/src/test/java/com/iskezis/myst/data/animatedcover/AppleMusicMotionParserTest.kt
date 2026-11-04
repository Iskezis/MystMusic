package com.iskezis.myst.data.animatedcover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMusicMotionParserTest {

    @Test
    fun extractsSquareMotionUrlFromSerializedServerData() {
        val html = """
            <html><body>
            <script type="application/json" id="serialized-server-data">
            {"data":[{"data":{"sections":[{"items":[{
              "id":"album-detail-header - 1",
              "videoArtwork":{"dictionary":{
                "motionDetailSquare":{"video":"https://mvod.example/apple/square.m3u8"}
              }}
            }]}]}}]}
            </script>
            </body></html>
        """.trimIndent()
        assertEquals(
            "https://mvod.example/apple/square.m3u8",
            AppleMusicMotionParser.extractMotionUrl(html, 1L),
        )
    }

    @Test
    fun returnsNullWithoutEditorialVideo() {
        val html = """
            <html><body>
            <script id="serialized-server-data">{"data":[]}</script>
            </body></html>
        """.trimIndent()
        assertNull(AppleMusicMotionParser.extractMotionUrl(html))
    }

    @Test
    fun ignoresUnsafeMotionUrl() {
        val html = """
            <html><body>
            <script id="serialized-server-data">
            {"videoArtwork":{"dictionary":{"motionDetailSquare":{"video":"http://insecure/video.m3u8"}}}}
            </script>
            </body></html>
        """.trimIndent()
        assertNull(AppleMusicMotionParser.extractMotionUrl(html))
    }
}
