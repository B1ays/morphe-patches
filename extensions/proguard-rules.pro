-dontobfuscate
-dontoptimize
-keepattributes *
-keep class app.morphe.** {
  *;
}
-keep class com.google.** {
  *;
}
# Proguard can strip away kotlin intrinsics methods that are used by extension Kotlin code. Unclear why.
-keep class kotlin.jvm.internal.Intrinsics {
    public static *;
}
# Extensions implement Kotlin function interfaces that are invoked by the app.
# Without this, R8 removes an interface that is not used as a type in the extension code.
-keep interface kotlin.jvm.functions.** {
  *;
}
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn javax.lang.model.element.Modifier
