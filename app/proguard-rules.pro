# Add project specific ProGuard rules here.
# Room, Hilt, and WorkManager ship their own consumer ProGuard rules, so no
# manual keep rules are needed for them in a standard MVP setup.

# Keep Room entity/DAO classes' generated members reachable for reflection-free codegen.
-keepclassmembers class com.mindfulscroll.app.data.** {
    <init>(...);
}
