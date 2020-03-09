/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted.phases;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.infrastructure.GraphProvider;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.graal.pointsto.util.CompletionExecutor.DebugContextRunnable;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.phases.AnalysisGraphBuilderPhase.AnalysisBytecodeParser;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugHandlersFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.java.BytecodeParser;
import org.graalvm.compiler.nodes.*;
import org.graalvm.compiler.nodes.cfg.ControlFlowGraph;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import org.graalvm.compiler.nodes.graphbuilderconf.InlineInvokePlugin;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.StoreFieldNode;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionValues;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.oracle.graal.pointsto.infrastructure.GraphProvider.*;

public class NativeImageInlineDuringParsingPlugin implements InlineInvokePlugin {

    // to use this plugin: native-image Example -H:+NativeInlineBeforeAnalysis
    public static class Options {
        @Option(help = "Inline methods during parsing before the static analysis.")//
        public static final HostedOptionKey<Boolean> NativeInlineBeforeAnalysis = new HostedOptionKey<>(false);

    }

    static class InvocationResult {
        static final InvocationResult ANALYSIS_TOO_COMPLICATED = new InvocationResult();
        static final InvocationResult NO_ANALYSIS = new InvocationResult();
    }

    private final boolean analysis;
    private final HostedProviders providers;

    public NativeImageInlineDuringParsingPlugin(boolean analysis, HostedProviders providers) {
        this.analysis = analysis;
        this.providers = providers;
    }

    @Override
    public InlineInfo shouldInlineInvoke(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args) {
        // b has info for caller
        int nodeCountCaller = b.getGraph().getNodeCount();
        // get graph for callee
        StructuredGraph graph = new StructuredGraph.Builder(b.getOptions(), b.getDebug()).method(method).build();
        int nodeCountCallee = graph.getNodeCount();
        // for now, look for method with small number of nodes, one or two
        if (nodeCountCallee <= 2) {
            System.out.println(b.getMethod().format("Caller: %n (class: %H), par: %p, ")
                    + "node count: " + nodeCountCaller
                    + method.format("\nCallee: %n (class: %H), par: %p, ")
                    + "node count: " + nodeCountCallee);
            // first, look for getters, setters and similar methods
            for(Node node : graph.getNodes()){
                System.out.println(node.toString());
                if (node instanceof LoadFieldNode)
                    System.out.println("Node represents a read of a static or instance field.");
                if (node instanceof StoreFieldNode)
                    System.out.println("Node represents a write to a static or instance field.");
                if (node instanceof ParameterNode)
                    System.out.println("Node represents a placeholder for an incoming argument to a function call.");
                if (node instanceof ConstantNode)
                    System.out.println("Node represents a constant");
            }
            System.out.println();
        }

        return null;
    }

    @Override
    public void notifyAfterInline(ResolvedJavaMethod methodToInline) {

    }

    @Override
    public void notifyBeforeInline(ResolvedJavaMethod methodToInline) {

    }

    @Override
    public void notifyNotInlined(GraphBuilderContext b, ResolvedJavaMethod method, Invoke invoke) {

    }
}


