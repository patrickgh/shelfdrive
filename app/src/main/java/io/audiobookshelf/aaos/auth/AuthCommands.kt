package io.audiobookshelf.aaos.auth

object AuthCommands {
    const val CMD_GET_AUTH_STATE = "io.shelfdrive.app.command.GET_AUTH_STATE"
    const val CMD_LOGIN = "io.shelfdrive.app.command.LOGIN"
    const val CMD_LOGOUT = "io.shelfdrive.app.command.LOGOUT"

    const val EXTRA_SERVER_URL = "io.shelfdrive.app.extra.SERVER_URL"
    const val EXTRA_USERNAME = "io.shelfdrive.app.extra.USERNAME"
    const val EXTRA_PASSWORD = "io.shelfdrive.app.extra.PASSWORD"

    const val RESULT_OK = 1
    const val RESULT_ERROR = 2
}
