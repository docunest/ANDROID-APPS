# Run as root through app_process by class name, with a main() (WifiDoctor, Split).
-keep class com.docunest.dock.RootWifi { public static void main(java.lang.String[]); }
-keep class com.docunest.dock.RootSplit { public static void main(java.lang.String[]); }
