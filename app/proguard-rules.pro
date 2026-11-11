# The root daemon is started by app_process with this entry point.
-keep class com.thorctl.daemon.Main {
    public static void main(java.lang.String[]);
}

# Hidden framework classes are accessed reflectively; nothing in the app needs keeping for that,
# but silence warnings about platform internals.
-dontwarn android.**
-dontwarn com.android.**

# Debug socket client (adb shell app_process ... com.thorctl.daemon.Cli '{"cmd":"get_state"}')
-keep class com.thorctl.daemon.Cli {
    public static void main(java.lang.String[]);
}
