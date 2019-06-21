package com.oracle.svm.core.posix;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.JDK11OrLater;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

@TargetClass( className = "java.net.AbstractPlainSocketImpl", onlyWith = JDK11OrLater.class)
@Platforms({Platform.LINUX.class, Platform.DARWIN.class})
final class Target_java_net_AbstractPlainSocketImpl {

    /* Do not re-format commented-out code: @formatter:off */
    // ported from: ./src/java.base/unix/native/libnet/SocketImpl.c
    //    38  JNIEXPORT jboolean JNICALL
    //    39  Java_java_net_AbstractPlainDatagramSocketImpl_isReusePortAvailable0(JNIEnv* env, jclass c1)
    @Substitute
    static boolean isReusePortAvailable0() {
        //    41      return (reuseport_available()) ? JNI_TRUE : JNI_FALSE;
        return JavaNetNetUtil.reuseport_available();
    }
    /* Do not re-format commented-out code: @formatter:on */
}
