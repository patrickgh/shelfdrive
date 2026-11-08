package io.audiobookshelf.aaos.server

import io.audiobookshelf.aaos.absapi.MediaProgressSummary
import java.io.IOException

class ServerProgressSupersededException(val progress: MediaProgressSummary) :
    IOException("A newer server position superseded the listening progress update.")
