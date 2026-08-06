#!/bin/sh
# 标准 gradlew 脚本（无 jar 时由 Android Studio / `gradle wrapper` 自动补全 wrapper jar）
# 这里提供一个最小可执行版本：若已有 gradle 则直接用，否则提示。
if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -Xmx2048m -classpath "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi
if command -v gradle >/dev/null 2>&1; then
  echo "[gradlew] 未找到 gradle-wrapper.jar，改用系统 gradle 并尝试生成 wrapper..."
  gradle wrapper 2>/dev/null
  if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    exec java -Xmx2048m -classpath "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
  fi
  exec gradle "$@"
fi
echo "未找到 Java 运行环境或 gradle。请用 Android Studio 打开本工程，或先安装 JDK 17。"
exit 1
