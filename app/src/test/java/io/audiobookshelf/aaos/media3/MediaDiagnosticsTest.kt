package io.audiobookshelf.aaos.media3

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDiagnosticsTest {
    @Test
    fun playerCommandsUseReadableDiagnosticNames() {
        assertEquals("PREPARE", playerCommandDiagnosticName(Player.COMMAND_PREPARE))
        assertEquals("STOP", playerCommandDiagnosticName(Player.COMMAND_STOP))
        assertEquals("CHANGE_MEDIA_ITEMS", playerCommandDiagnosticName(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertEquals("COMMAND_9999", playerCommandDiagnosticName(9999))
    }
}
