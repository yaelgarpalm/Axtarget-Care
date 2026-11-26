# All platform components are retained by the Android manifest rules.

# MediaPipe's native bridge looks up framework classes and methods by name.
-keep class com.google.mediapipe.framework.** { *; }
# Tasks Vision omits the optional graph-template and profiler protos that AxTarget Care never calls.
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate

# MediaPipe's graph loader instantiates message types such as Any reflectively
# and reads generated fields by name to parse the two model graphs.
-keep class com.google.protobuf.** { *; }
-keep class com.google.mediapipe.** extends com.google.protobuf.GeneratedMessageLite { *; }

# Flogger's caller lookup needs its original stack frames during Graph initialization.
-keep class com.google.common.flogger.** { *; }

# Credential Manager discovers the Play services provider from its metadata.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
