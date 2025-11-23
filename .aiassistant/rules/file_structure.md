---
apply: by model decision
instructions: When a new file need to be created.
---

You must decide the file's location and name based on the area of the codebase that it represents. Examples:
- If creating `*UseCase.kt` for new shared functionality, then it should go in `/shared/src/commonMain/kotlin/link/socket/ampere/domain/usecase`
- If you are providing the `actual` definition for a new `commonMain` value/function, then it should stay within that module's folder, and the file must end in `*.$platform.kt`. Example:
  - There is `expect fun foo()` defined in `/shared/src/commonMain/kotlin/link/socket/ampere/io/Foo.kt`
  - The Android `actual fun foo()` should be generated in `/shared/src/androidMain/kotlin/link/socket/ampere/io/Foo.android.kt`
  - The Desktop `actual fun foo()` should be generated in `/shared/src/jvmMain/kotlin/link/socket/ampere/io/Foo.jvm.kt`
- If you are defining new functionality that is specific to _only_ one platform then it must go in the folder for that platform. Examples:
  - The Android app is defined in `MainActivity.kt` and `AndroidMainfest.xml`, which are both in `/androidApp/src/androidMain/*`
  - The Desktop app is defined in `Main.kt`, which is in `/desktopApp/src/jvmMain/kotlin/link/socket/ampere/Main.kt`
