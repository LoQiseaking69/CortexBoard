# JNI bridge + callbacks invoked from native code
-keep class com.hermitech.cortexboard.llm.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# WorkManager workers are instantiated reflectively
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
