// Android Studio와 명령행 빌드가 같은 Android/Compose 플러그인을 사용한다.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
}
