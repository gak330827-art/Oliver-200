# ═══════════════════════════════════════════════════════════════════════════
#  Oliver-200 · правила R8 для release-сборки
#  Подпись: OLIVER-200 · см. SIGNATURES.txt
# ═══════════════════════════════════════════════════════════════════════════

# Логи не должны попадать в боевую сборку: access-token, попавший в logcat,
# читается любым приложением с READ_LOGS и на рутованном устройстве.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Имена и строки — лишняя подсказка тому, кто разбирает APK.
-repackageclasses 'o'
-allowaccessmodification
-optimizationpasses 5

# Стек-трейсы остаются читаемыми через mapping.txt (не публикуем его!).
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Kotlin
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**

# Fragment'ы создаются рефлексией фреймворком.
-keep public class * extends androidx.fragment.app.Fragment

# Ядро лаунчера обфусцируется целиком: публичного API наружу у него нет.
