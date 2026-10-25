# =============================================================================
# SpeedyNote Android Build Environment
# =============================================================================
# This Dockerfile creates a reproducible environment for building SpeedyNote
# for Android arm64-v8a and armeabi-v7a (32-bit).
#
# Contents:
#   - Ubuntu 22.04 LTS base
#   - Android SDK (API 33) + NDK r23b (23.1.7779620)
#   - Qt 6.4.3 for Android arm64-v8a + armv7 (32-bit)
#   - Qt 6.4.3 host tools (Linux x86_64)
#   - CMake, Ninja, OpenJDK 17
#
# Build:
#   docker build -t speedynote-android:latest .
#
# Run:
#   docker run -it --rm -v $(pwd)/..:/workspace speedynote-android:latest
#
# =============================================================================

FROM ubuntu:22.04

LABEL maintainer="SpeedyNote Developers"
LABEL description="Android build environment for SpeedyNote"

# Prevent interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# =============================================================================
# System Dependencies
# =============================================================================
RUN apt-get update && apt-get install -y --no-install-recommends \
    # Build tools
    build-essential \
    cmake \
    ninja-build \
    pkg-config \
    # Version control
    git \
    # Download tools
    wget \
    curl \
    unzip \
    # Java (required for Android SDK)
    openjdk-17-jdk \
    # Python (for aqtinstall)
    python3 \
    python3-pip \
    python3-venv \
    # SSL certificates (for downloads)
    ca-certificates \
    # Useful utilities
    file \
    && rm -rf /var/lib/apt/lists/*

# =============================================================================
# Environment Variables
# =============================================================================
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=${ANDROID_SDK_ROOT}
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Qt version to install
# Qt 6.4.3 is pinned for the Android 7 (API 24/25) e-ink tablet adaptation:
#   - Qt 6.4.x is the LAST Qt6 branch whose official minimum is Android 6.0
#     (API 23), so it is the only Qt6 that can legitimately target Android 7.
#   - Qt 6.5-6.7 raise the floor to Android 8.0 (API 26); Qt 6.8+ to Android 9
#     (API 28). Their prebuilt libraries are compiled against those APIs and
#     will not load on an Android 7 device, which is why the whole toolchain
#     (Qt, NDK, AGP, androidx) is downgraded together here.
#   - 6.4.3 is the final 6.4 patch release.
ENV QT_VERSION=6.4.3
ENV QT_ROOT=/opt/qt
ENV QT_ANDROID=${QT_ROOT}/${QT_VERSION}/android_arm64_v8a
ENV QT_ANDROID_ARMV7=${QT_ROOT}/${QT_VERSION}/android_armv7
# Note: aqtinstall uses "linux_gcc_64" as arch name but installs to "gcc_64"
ENV QT_HOST=${QT_ROOT}/${QT_VERSION}/gcc_64

# NDK version
# Qt 6.4 was built with NDK r23b; user apps MUST use the same NDK to avoid
# missing-symbol errors (notably __emutls_get_address). r23b = 23.1.7779620.
ENV NDK_VERSION=23.1.7779620
ENV ANDROID_NDK_ROOT=${ANDROID_SDK_ROOT}/ndk/${NDK_VERSION}

# API levels
# min 24 = Android 7.0 (the target tablet). compile/target 33 is the highest
# that pairs cleanly with the Qt 6.4 + AGP 7.x toolchain.
ENV ANDROID_MIN_SDK=24
ENV ANDROID_TARGET_SDK=33

# Add tools to PATH
ENV PATH="${QT_HOST}/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}"

# =============================================================================
# Install Android SDK & NDK
# =============================================================================
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    # Download Android command-line tools
    wget -q -O /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools && \
    mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Accept licenses
RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true

# Install SDK components
RUN sdkmanager --install \
    "platforms;android-${ANDROID_TARGET_SDK}" \
    "build-tools;33.0.2" \
    "ndk;${NDK_VERSION}" \
    "platform-tools"

# =============================================================================
# Install Qt via aqtinstall
# =============================================================================
# Create virtual environment for aqtinstall to avoid pip externally-managed error
RUN python3 -m venv /opt/aqt-venv && \
    /opt/aqt-venv/bin/pip install --no-cache-dir aqtinstall

# Install Qt for Android (arm64-v8a)
RUN /opt/aqt-venv/bin/aqt install-qt linux android ${QT_VERSION} android_arm64_v8a \
    -O ${QT_ROOT} \
    --modules qtimageformats

# Install Qt for Android (armeabi-v7a / 32-bit ARM)
RUN /opt/aqt-venv/bin/aqt install-qt linux android ${QT_VERSION} android_armv7 \
    -O ${QT_ROOT} \
    --modules qtimageformats

# Install Qt host tools (needed for moc, uic, rcc, androiddeployqt, etc.)
# Note: For Qt 6.x, architecture is "linux_gcc_64" not "gcc_64".
# Intentionally NO --modules here: the host install only needs the build tools
# themselves. The qtimageformats plugin is a *runtime* component that ships from
# the Android Qt install above and gets packaged into the APK; requesting it for
# the desktop host is unnecessary and is what made this step fail on Qt 6.4.3.
RUN /opt/aqt-venv/bin/aqt install-qt linux desktop ${QT_VERSION} linux_gcc_64 \
    -O ${QT_ROOT}

# =============================================================================
# Pin the Gradle wrapper version (AGP 7.4.2 + JDK 17 compatibility)
# =============================================================================
# androiddeployqt copies the Gradle wrapper bundled inside each Qt install
# (<qt>/.../src/3rdparty/gradle/.../gradle-wrapper.properties) into every
# build, so whatever version Qt 6.4 ships would otherwise be used. Pin it
# explicitly to satisfy three hard constraints at once:
#   - AGP 7.4.2 (build.gradle) requires Gradle >= 7.5.
#   - JDK 17 (installed above) requires Gradle >= 7.3 just to launch.
#   - Gradle 8.x would in turn require AGP 8.x, so we must stay on 7.x.
# 7.6.4 is the final, most-patched Gradle 7.x release and meets all three.
# `find` is used so this is robust to the exact template path layout.
RUN for f in $(find ${QT_ROOT} -path '*/gradle/wrapper/gradle-wrapper.properties'); do \
        sed -i 's|^distributionUrl=.*|distributionUrl=https\\://services.gradle.org/distributions/gradle-7.6.4-bin.zip|' "$f"; \
        echo "Pinned Gradle wrapper -> 7.6.4 in: $f"; \
    done

# =============================================================================
# Verify Installation
# =============================================================================
RUN echo "=== Verifying installation ===" && \
    echo "Android SDK: $(ls ${ANDROID_SDK_ROOT}/platforms/)" && \
    echo "Android NDK: $(ls ${ANDROID_SDK_ROOT}/ndk/)" && \
    echo "Qt Android arm64: $(ls ${QT_ANDROID}/bin/ | head -5)" && \
    echo "Qt Android armv7: $(ls ${QT_ANDROID_ARMV7}/bin/ | head -5)" && \
    echo "Qt Host: $(${QT_HOST}/bin/qmake --version)" && \
    echo "CMake: $(cmake --version | head -1)" && \
    echo "Java: $(java --version | head -1)"

# =============================================================================
# Workspace Setup
# =============================================================================
WORKDIR /workspace

# Default command
CMD ["/bin/bash"]

