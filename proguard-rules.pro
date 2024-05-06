# Keep entrypoint and everything reachable from it.
-keep class com.android.tmovvm.Main {
    public static void main(java.lang.String[]);
}
