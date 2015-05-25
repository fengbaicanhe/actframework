/**
 * ASM: a very small and fast Java bytecode manipulation framework
 * Copyright (c) 2000-2011 INRIA, France Telecom
 * All rights reserved.
 * <p/>
 * Redistribution and use in srccode and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of srccode code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * 3. Neither the className of the copyright holders nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * <p/>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGE.
 */
package act.asm.tree.analysis;

import act.asm.tree.LabelNode;
import act.asm.tree.JumpInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A method subroutine (corresponds to a JSR instruction).
 *
 * @author Eric Bruneton
 */
class Subroutine {

    LabelNode start;

    boolean[] access;

    List<JumpInsnNode> callers;

    private Subroutine() {
    }

    Subroutine(final LabelNode start, final int maxLocals,
               final JumpInsnNode caller) {
        this.start = start;
        this.access = new boolean[maxLocals];
        this.callers = new ArrayList<JumpInsnNode>();
        callers.add(caller);
    }

    public Subroutine copy() {
        Subroutine result = new Subroutine();
        result.start = start;
        result.access = new boolean[access.length];
        System.arraycopy(access, 0, result.access, 0, access.length);
        result.callers = new ArrayList<JumpInsnNode>(callers);
        return result;
    }

    public boolean merge(final Subroutine subroutine) throws AnalyzerException {
        boolean changes = false;
        for (int i = 0; i < access.length; ++i) {
            if (subroutine.access[i] && !access[i]) {
                access[i] = true;
                changes = true;
            }
        }
        if (subroutine.start == start) {
            for (int i = 0; i < subroutine.callers.size(); ++i) {
                JumpInsnNode caller = subroutine.callers.get(i);
                if (!callers.contains(caller)) {
                    callers.add(caller);
                    changes = true;
                }
            }
        }
        return changes;
    }
}