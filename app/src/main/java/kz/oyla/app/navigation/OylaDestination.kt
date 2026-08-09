package kz.oyla.app.navigation

enum class OylaDestination(val route: String) {
    ROLE_SELECTION("role_selection"),
    CREATE_PIN("create_pin"),
    CHANGE_PIN("change_pin"),
    SPECIALIST_HOME("specialist_home"),
    SPECIALIST_SETTINGS("specialist_settings"),
    CHILD_SETTINGS("child_settings"),
    VERIFY_PIN("verify_pin"),
    CHILD_IDLE("child_idle"),
    CHILD_CONNECT("child_connect"),
    CREATE_SESSION("create_session"),
    SELECT_SPECIALIST("select_specialist"),
    SELECT_CHILD("select_child"),
    SELECT_CHILD_DEVICE("select_child_device"),
    CONFIRM_LESSON("confirm_lesson"),
    SPECIALIST_WAITING("specialist_waiting"),
    CHILD_WAITING("child_waiting"),
    SPECIALIST_EXERCISE("specialist_exercise"),
    SPECIALIST_SUMMARY("specialist_summary"),
    CHILD_EXERCISE("child_exercise")
}
