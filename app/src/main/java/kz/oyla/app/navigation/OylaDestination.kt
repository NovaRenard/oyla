package kz.oyla.app.navigation

enum class OylaDestination(val route: String) {
    ROLE_SELECTION("role_selection"),
    CREATE_PIN("create_pin"),
    SPECIALIST_HOME("specialist_home"),
    SPECIALIST_SETTINGS("specialist_settings"),
    VERIFY_PIN("verify_pin"),
    CHILD_CONNECT("child_connect")
}
