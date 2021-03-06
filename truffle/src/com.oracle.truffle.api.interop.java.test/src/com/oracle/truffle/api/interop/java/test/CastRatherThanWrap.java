/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.api.interop.java.test;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.MessageResolution;
import com.oracle.truffle.api.interop.Resolve;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;

@SuppressWarnings("deprecation")
public class CastRatherThanWrap {

    private Runnable exec;

    @MessageResolution(receiverType = ExecutableObject.class)
    static final class ExecutableObject implements Runnable, TruffleObject {
        @Override
        public void run() {
        }

        static boolean isInstance(TruffleObject obj) {
            return obj instanceof ExecutableObject;
        }

        @Override
        public ForeignAccess getForeignAccess() {
            return ExecutableObjectForeign.ACCESS;
        }

        @SuppressWarnings("unused")
        @Resolve(message = "IS_EXECUTABLE")
        abstract static class IsExecutableExecutable extends Node {
            boolean access(ExecutableObject obj) {
                return true;
            }
        }

        @SuppressWarnings("unused")
        @Resolve(message = "EXECUTE")
        abstract static class ExecuteExecutable extends Node {
            String access(ExecutableObject obj, Object... args) {
                return "ExecuteExecutable.noop()";
            }
        }
    }

    public void acceptRunnable(Runnable runnable) {
        this.exec = runnable;
    }

    @Test
    public void castTruffleObjectIfPossible() throws Exception {
        com.oracle.truffle.api.vm.PolyglotEngine.newBuilder().build().dispose();
        ExecutableObject execObj = new ExecutableObject();
        TruffleObject thiz = com.oracle.truffle.api.interop.java.JavaInterop.asTruffleObject(this);

        ForeignAccess.sendInvoke(Message.INVOKE.createNode(), thiz, "acceptRunnable", execObj);

        assertEquals("ExecutableObject was passed as Runnable unwrapped", execObj, this.exec);
    }
}
