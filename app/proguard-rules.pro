# JavaMail resolves SMTP transports and MIME handlers by class names from
# META-INF provider files, so R8 cannot see these references statically.
-keep class com.sun.mail.smtp.** { *; }
-keep class com.sun.mail.handlers.** { *; }
-keep class com.sun.mail.util.** { *; }
-keep class com.sun.activation.registries.** { *; }

-dontwarn com.sun.mail.**
-dontwarn javax.activation.**
-dontwarn javax.mail.**
