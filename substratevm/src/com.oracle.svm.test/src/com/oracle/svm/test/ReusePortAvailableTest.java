package com.oracle.svm.test;

import java.net.Socket;

import org.junit.Test;

public class ReusePortAvailableTest {

    @Test
    public void testReusePortAvailable() throws Exception {
        Socket s = new Socket();
        System.err.println( s.supportedOptions() );
    }
}
