# Release builds shrink and optimize with R8 but keep every class and method name, and nothing
# below depends on obfuscation. R8 still writes file:line in stack traces as "r8-map-id-…"
# (merged lambdas may also show under another class), so keep each release's
# build/outputs/mapping/release/mapping.txt with its APK and read box traces through `retrace`.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# EnergyClient moves requests between this app's own processes with Java serialization and copies
# DTO fields back by reflection, so serializable classes keep their fields and hooks as written.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
