# arm64-only: native-image can't cross-compile. On x86_64 hosts enable QEMU once:
#   docker run --privileged --rm tonistiigi/binfmt --install arm64
# bookworm's glibc 2.36 keeps the binary loadable on Sailfish OS 5.2+ (glibc 2.41).
FROM arm64v8/debian:bookworm

# dbus + bluez are for the tracing-agent run in build-native.sh.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl build-essential zlib1g-dev file \
      dbus bluez rpm \
 && rm -rf /var/lib/apt/lists/*

# GraalVM 21's bytecode parser chokes on Kotlin 2.3 output; 25 works.
RUN curl -fsSL -o /tmp/graalvm.tar.gz \
      https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-25.0.2/graalvm-community-jdk-25.0.2_linux-aarch64_bin.tar.gz \
 && mkdir -p /opt/graalvm \
 && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 \
 && rm /tmp/graalvm.tar.gz

ENV JAVA_HOME=/opt/graalvm
ENV PATH=/opt/graalvm/bin:$PATH

WORKDIR /work
