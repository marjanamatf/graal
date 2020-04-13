
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
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.graal.pointsto.util.CompletionExecutor.DebugContextRunnable;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.annotate.NeverInline;
import com.oracle.svm.core.annotate.NeverInlineTrivial;
import com.oracle.svm.core.annotate.RestrictHeapAccess;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.graal.jdk.ArraycopySnippets;
import com.oracle.svm.core.jdk.InternalVMMethod;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.phases.AnalysisGraphBuilderPhase.AnalysisBytecodeParser;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugHandlersFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.java.BytecodeParser;
import org.graalvm.compiler.java.GraphBuilderPhase;
import org.graalvm.compiler.nodeinfo.Verbosity;
import org.graalvm.compiler.nodes.*;
import org.graalvm.compiler.nodes.cfg.ControlFlowGraph;
import org.graalvm.compiler.nodes.extended.ForeignCallNode;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import org.graalvm.compiler.nodes.graphbuilderconf.InlineInvokePlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.IntrinsicContext;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.StoreFieldNode;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.OptimisticOptimizations;
import org.graalvm.compiler.replacements.Snippets;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.oracle.graal.pointsto.infrastructure.GraphProvider.*;
import static com.oracle.svm.hosted.phases.SharedGraphBuilderPhase.*;

public class NativeImageInlineDuringParsingPlugin implements InlineInvokePlugin {

    // to use this plugin: native-image Example -H:+InlineBeforeAnalysis
    public static class Options {
        @Option(help = "Inline methods during parsing before the static analysis.")//
        public static final HostedOptionKey<Boolean> InlineBeforeAnalysis = new HostedOptionKey<>(false);

    }

    static final class CallSite {
        final AnalysisMethod caller;
        final int bci;

        CallSite(AnalysisMethod caller, int bci) {
            this.caller = caller;
            this.bci = bci;
        }

        @Override
        public int hashCode() {
            return caller.hashCode() * 31 + bci;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            } else if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            CallSite other = (CallSite) obj;
            return bci == other.bci && caller.equals(other.caller);
        }

        @Override
        public String toString() {
            return caller.format("%h.%n(%p)") + "@" + bci;
        }
    }

    static class InvocationResult {
        static final InvocationResult ANALYSIS_TOO_COMPLICATED = new InvocationResult();
        static final InvocationResult NO_ANALYSIS = new InvocationResult();
    }

    public static class InvocationResultInline extends InvocationResult {
        final CallSite site;
        final AnalysisMethod callee;
        final Map<CallSite, InvocationResultInline> children;

        public InvocationResultInline(CallSite site, AnalysisMethod callee) {
            this.site = site;
            this.callee = callee;
            this.children = new HashMap<>();
        }

        @Override
        public String toString() {
            return append(new StringBuilder(), "").toString();
        }

        private StringBuilder append(StringBuilder sb, String indentation) {
            sb.append(site).append(" -> ").append(callee.format("%h.%n(%p)"));
            String newIndentation = indentation + "  ";
            for (InvocationResultInline child : children.values()) {
                sb.append(System.lineSeparator()).append(newIndentation);
                child.append(sb, newIndentation);
            }
            return sb;
        }
    }

    public static class InvocationData {
        private final ConcurrentMap<AnalysisMethod, ConcurrentMap<Integer, InvocationResult>> data = new ConcurrentHashMap<>();

        private ConcurrentMap<Integer, InvocationResult> bciMap(ResolvedJavaMethod method) {
            AnalysisMethod key;
            if (method instanceof AnalysisMethod) {
                key = (AnalysisMethod) method;
            } else {
                key = ((HostedMethod) method).getWrapped();
            }

            return data.computeIfAbsent(key, unused -> new ConcurrentHashMap<>());
        }

        InvocationResult get(ResolvedJavaMethod method, int bci) {
            return bciMap(method).get(bci);
        }

        InvocationResult putIfAbsent(ResolvedJavaMethod method, int bci, InvocationResult value) {
            return bciMap(method).putIfAbsent(bci, value);
        }

        public void onCreateInvoke(GraphBuilderContext b, int invokeBci, boolean analysis, ResolvedJavaMethod callee) {
            if (b.getDepth() == 0) {

                if (callee != null && callee.equals(b.getMetaAccess().lookupJavaMethod(SubstrateClassInitializationPlugin.ENSURE_INITIALIZED_METHOD))) {
                    return;
                }

                ConcurrentMap<Integer, InvocationResult> map = bciMap(b.getMethod());
                if (analysis) {
                    map.putIfAbsent(invokeBci, InvocationResult.NO_ANALYSIS);
                } else {
                    InvocationResult state = map.get(invokeBci);
                    if (state != InvocationResult.ANALYSIS_TOO_COMPLICATED && state != InvocationResult.NO_ANALYSIS) {
                        throw VMError.shouldNotReachHere("Missing information for call site: " + b.getMethod().asStackTraceElement(invokeBci));
                    }
                }
            }
        }
    }


    private final boolean analysis;
    private final HostedProviders providers;

    public NativeImageInlineDuringParsingPlugin(boolean analysis, HostedProviders providers) {
        this.analysis = analysis;
        this.providers = providers;
    }

    @Override
    public InlineInfo shouldInlineInvoke(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args) {

        if (Snippets.class.isAssignableFrom(((AnalysisMethod) (b.getMethod())).getDeclaringClass().getJavaClass())) {
            /* We are not interfering with any snippet */
            // System.out.println(((AnalysisMethod) (b.getMethod())).getDeclaringClass().getJavaClass());
            return null;
        }

        if (Snippets.class.isAssignableFrom(((AnalysisMethod)method).getDeclaringClass().getJavaClass())) {
            /* We are not interfering with any snippet */
            // System.out.println(((AnalysisMethod)method).getDeclaringClass().getJavaClass());
            return null;
        }


        if(b.getMethod().getDeclaringClass().isAnnotationPresent(InternalVMMethod.class)) {
            System.out.println("InternalVMMethod: " + method.getName());
            /* We are not interfering with any internal vmmethod */
            return null;
        }

        if(method.getDeclaringClass().isAnnotationPresent(InternalVMMethod.class)) {
            System.out.println("InternalVMMethod: " + method.getName());
            /* We are not interfering with any internal vmmethod */
            return null;

        }

        /*
        InvocationData data = ((SharedBytecodeParser) b).inlineInvocationData;
        if (data == null) {
            throw VMError.shouldNotReachHere("must not use SubstrateInlineDuringParsingPlugin when bytecode parser does not have InvocationData");
        }
        */

        if (b.parsingIntrinsic()) {
            /* We are not interfering with any intrinsic method handling. */
            return null;
        }

        if (method.getAnnotation(NeverInline.class) != null || method.getAnnotation(NeverInlineTrivial.class) != null) {
            return null;
        }

        if (method.getAnnotation(RestrictHeapAccess.class) != null || method.getAnnotation(Uninterruptible.class) != null ||
                b.getMethod().getAnnotation(RestrictHeapAccess.class) != null || b.getMethod().getAnnotation(Uninterruptible.class) != null) {
            /*
             * Caller or callee have an annotation that might prevent inlining. We don't check the
             * exact condition but instead always bail out for simplicity.
             */
            return null;
        }

        if (method.equals(b.getMetaAccess().lookupJavaMethod(SubstrateClassInitializationPlugin.ENSURE_INITIALIZED_METHOD))) {
            return null;
        }

        CallSite callSite = new CallSite(toAnalysisMethod(b.getMethod()), b.bci());

        InvocationResult inline;
        if (b.getDepth() > 0) {
            /*
             * We already decided to inline the first callee into the root method, so now
             * recursively inline everything.
             */
            inline = ((SharedBytecodeParser) b.getParent()).inlineDuringParsingState.children.get(callSite);

        } else {
            InvocationResult newResult;

            if (!method.hasBytecodes()) {
                /* Native method. */
                newResult = InvocationResult.ANALYSIS_TOO_COMPLICATED;
            } else if (method.isSynchronized()) {
                /*
                 * Synchronization operations will always bring us above the node limit, so no point in
                 * starting an analysis.
                 */
                newResult = InvocationResult.ANALYSIS_TOO_COMPLICATED;

            } else if (((AnalysisMethod) method).buildGraph(b.getDebug(), method, providers, Purpose.ANALYSIS) != null) {
                /* Method has a manually constructed graph via GraphProvider. */
                newResult = InvocationResult.ANALYSIS_TOO_COMPLICATED;

            } else if (providers.getGraphBuilderPlugins().getInvocationPlugins().lookupInvocation(method) != null) {
                /* Method has an invocation plugin that we must not miss. */
                newResult = InvocationResult.ANALYSIS_TOO_COMPLICATED;
            } else {
                /* try to detect simple methods for inline */
                newResult = analyzeMethod(b, (AnalysisMethod) method, callSite);
                if(newResult instanceof InvocationResultInline){
                    if(((SharedBytecodeParser) b).inlineDuringParsingState != null){
                        InvocationResultInline inlineState = (InvocationResultInline) newResult;
                        ((SharedBytecodeParser) b).inlineDuringParsingState.children.put(inlineState.site, inlineState);
                        System.out.println("Method to inline: " + method.getName());
                        return InlineInfo.createStandardInlineInfo(method);
                    }
                }
            }
            // InvocationResult existingResult = data.putIfAbsent(b.getMethod(), b.bci(), newResult);
            inline = newResult;
        }
        if (inline instanceof InvocationResultInline) {
            InvocationResultInline inlineData = (InvocationResultInline) inline;
            ((SharedBytecodeParser) b).inlineDuringParsingState = inlineData;
            System.out.println("Method to inline: " + method.getName());
            return InlineInfo.createStandardInlineInfo(method);
        }
        else
            return null;
    }

    InvocationResult analyzeMethod(GraphBuilderContext b, AnalysisMethod method, CallSite callSite) {
        // build graph for method and analyze
        // b has info for caller
        int nodeCountCaller = b.getGraph().getNodeCount();
        // get graph for callee
        StructuredGraph graph = new StructuredGraph.Builder(b.getOptions(), b.getDebug()).method(method).build();
        AnalysisGraphBuilderPhase graphbuilder = new AnalysisGraphBuilderPhase(providers, ((SharedBytecodeParser) b).getGraphBuilderConfig(), OptimisticOptimizations.NONE, null, providers.getWordTypes());
        graphbuilder.apply(graph);
        int nodeCountCallee = graph.getNodeCount();

        System.out.println("\nbuild structured graph: " + b.getMethod().format("Caller: %n (class: %H), par: %p, ")
                + "node count: " + nodeCountCaller
                + method.format("\nCallee: %n (class: %H), par: %p, ")
                + "node count: " + nodeCountCallee);

        int countFrameStates = 0;
        FrameState frameState = null;
        int countForeignCall = 0;
        boolean hasLoadField = false;
        boolean hasStoreField = false;

        for (Node node : graph.getNodes()) {
            System.out.print(node.toString());
            if (node instanceof LoadFieldNode) {
                hasLoadField = true;
                System.out.print(" - node represents a read of a static or instance field.");
            }
            if (node instanceof StoreFieldNode) {
                hasStoreField = true;
                System.out.print(" - node represents a write to a static or instance field.");
            }
            if (node instanceof ParameterNode)
                System.out.print(  " - node represents a placeholder for an incoming argument to a function call.");
            if (node instanceof ConstantNode)
                System.out.print(" - node represents a constant");
            if (node instanceof FrameState) {
                countFrameStates++;
                frameState = (FrameState) node;
                System.out.println(" - " + frameState.toString(Verbosity.All));
            }
            if(node instanceof ForeignCallNode) {
                System.out.println(" - foreign call " + node.toString(Verbosity.All));
                return InvocationResult.ANALYSIS_TOO_COMPLICATED;
            }
            System.out.println(" ");
        }

        if (countFrameStates == 0)
            return new InvocationResultInline(callSite, method);

        if (countFrameStates == 1 && frameState.stackSize() == 0)
            return new InvocationResultInline(callSite, method);

        /* last frame state with stack size zero */
        if (frameState.stackSize() == 0) {
            if(hasStoreField || hasLoadField)
                return new InvocationResultInline(callSite, method);
        }

        return InvocationResult.ANALYSIS_TOO_COMPLICATED;
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

    static AnalysisMethod toAnalysisMethod(ResolvedJavaMethod method) {
        if (method instanceof AnalysisMethod) {
            return (AnalysisMethod) method;
        } else {
            return ((HostedMethod) method).getWrapped();
        }
    }
}