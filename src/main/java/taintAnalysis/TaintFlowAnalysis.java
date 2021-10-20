package taintAnalysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;
import taintAnalysis.pointsToAnalysis.MethodInfo;
import taintAnalysis.pointsToAnalysis.pointsToSet.ObjRefPointsToSet;
import taintAnalysis.pointsToAnalysis.pointsToSet.PointsToSet;
import taintAnalysis.sourceSinkManager.ISourceSinkManager;
import taintAnalysis.taintWrapper.ITaintWrapper;
import taintAnalysis.utility.PhantomIdentityStmt;
import taintAnalysis.utility.PhantomRetStmt;

import java.util.*;

import static assertion.Assert.assertNotNull;

/**
 * The forward analysis framework for taint analysis.
 * This framework deals with intra-procedural analysis.
 */
public class TaintFlowAnalysis extends ForwardFlowAnalysis<Unit, Set<Taint>> {
    // The logger to record log information
    private final Logger logger = LoggerFactory.getLogger(getClass());
    // The call graph to get a more precise call method
    // It is not null if spark option is enabled
    private static final CallGraph cg = Scene.v().hasCallGraph() ? Scene.v().getCallGraph() : null;
    // Whether there is a change in the analysis of this method
    // If not, we have reached a fixed point in this method
    private boolean changed = false;
    // The body and method of this intra-procedural analysis
    private final Body body;
    private final SootMethod method;
    // It checks whether a method is a source or sink
    private final ISourceSinkManager sourceSinkManager;
    // It transfers taints from some library methods,
    // which is an approximation to save time and memory
    private final ITaintWrapper taintWrapper;
    // The taint at the entry of this method,
    // which can be viewed as a context with 1-approximation
    private final Taint entryTaint;
    // The global program states,
    // which is a set of discovered taints for this object, return value and parameters
    // in each method in each context
    private final Map<SootMethod, Map<Taint, List<Set<Taint>>>> methodSummary;
    // The program states in current method
    private final Map<Taint, List<Set<Taint>>> currMethodSummary;
    // It caches taints in each method to avoid repetitious taint construction
    private final Map<SootMethod, Map<Taint, Taint>> methodTaintCache;
    // The taint cache in current method
    private final Map<Taint, Taint> currTaintCache;
    // The return statement of this method
    private final PhantomRetStmt phantomRetStmt;
    // The set of taints that is in source in this method
    private final Set<Taint> sources;
    // The set of taints that has reached the sink in this method
    private final Set<Taint> sinks;
    // Whether using the result of points-to analysis in this taint analysis
    private final boolean use_points_to;
    // The points-to info of all methods,
    // It is empty if we disable use_points_to
    private final Map<SootMethod, MethodInfo> globalMethodInfo;
    // The points-to info of current methods,
    // It is null if we disable use_points_to
    private final MethodInfo currMethodInfo;
    // The instance reference use info for all sink methods
    private final Map<SootMethod, Map<JInstanceFieldRef, Integer>> globalSinkRefUseInfo;
    // Three data structures below are used for the initialization of uniqueStmt
    // It stores the overall count number of string of each statement
    private final Map<String, Integer> stmtStrCounter;
    // It stores the count id of each statement
    private final Map<Stmt, Integer> countedStmtCache;
    // It stores the generated UniqueStmt(in order to reduce repetitious object generation)
    private final Map<UniqueStmt, UniqueStmt> uniqueStmtCache;
    // For testing whether a taint should be propagated into sink
    private final FieldUseChecker fieldUseChecker;
    // For testing
    // Original sink taints that must not be used in sink
    public final Set<Taint> mustNotUsedSinks;
    // Original sink taints that may be used in sink due to further method call
    public final Set<Taint> mayUseSinks;
    // Original sink taints that is unknown to be used in sink due to lack of callee body
    public final Set<Taint> unknownSinks;

    public TaintFlowAnalysis(Body body, ISourceSinkManager sourceSinkManager) {
        this(body, sourceSinkManager, Taint.getEmptyTaint(), new HashMap<>(),
                new HashMap<>(), null, false, new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new FieldUseChecker());
    }

    /** The constructor for intra-procedural analysis */
    public TaintFlowAnalysis(Body body,
                             ISourceSinkManager sourceSinkManager,
                             Taint entryTaint,
                             Map<SootMethod, Map<Taint, List<Set<Taint>>>> methodSummary,
                             Map<SootMethod, Map<Taint, Taint>> methodTaintCache,
                             ITaintWrapper taintWrapper) {
        this(body, sourceSinkManager, entryTaint, methodSummary, methodTaintCache,
                taintWrapper, false, new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new FieldUseChecker());
    }

    /**
     * A complete constructor
     */
    public TaintFlowAnalysis(Body body,
                             ISourceSinkManager sourceSinkManager,
                             Taint entryTaint,
                             Map<SootMethod, Map<Taint, List<Set<Taint>>>> methodSummary,
                             Map<SootMethod, Map<Taint, Taint>> methodTaintCache,
                             ITaintWrapper taintWrapper,
                             boolean use_points_to,
                             Map<SootMethod, MethodInfo> globalMethodInfo,
                             Map<SootMethod, Map<JInstanceFieldRef, Integer>> globalSinkRefUseInfo,
                             Map<String, Integer> stmtStrCounter,
                             Map<Stmt, Integer> countedStmtCache,
                             Map<UniqueStmt, UniqueStmt> uniqueStmtCache,
                             FieldUseChecker fieldUseChecker) {
        super(new ExceptionalUnitGraph(body));
        this.body = body;
        this.method = body.getMethod();
        this.sourceSinkManager = sourceSinkManager;
        this.entryTaint = entryTaint;
        this.methodSummary = methodSummary;
        this.methodTaintCache = methodTaintCache;
        this.sources = new HashSet<>();
        this.sinks = new HashSet<>();
        this.taintWrapper = taintWrapper;
        this.phantomRetStmt = PhantomRetStmt.getInstance(method);
        this.use_points_to = use_points_to;
        this.globalMethodInfo = globalMethodInfo;
        this.globalSinkRefUseInfo = globalSinkRefUseInfo;
        this.stmtStrCounter = stmtStrCounter;
        this.countedStmtCache = countedStmtCache;
        this.uniqueStmtCache = uniqueStmtCache;

        this.fieldUseChecker = fieldUseChecker;
        // For testing
        mustNotUsedSinks = new HashSet<>();
        mayUseSinks = new HashSet<>();
        unknownSinks = new HashSet<>();


        // Sanity check
        assertNotNull(body);
        assertNotNull(sourceSinkManager);
        assertNotNull(entryTaint);
        assertNotNull(methodSummary);
        assertNotNull(methodTaintCache);
        assertNotNull(globalMethodInfo);
        assertNotNull(globalSinkRefUseInfo);

        // Initialize methodSummary and methodTaintCache for current method (if not done yet)
        methodSummary.putIfAbsent(method, new HashMap<>());
        this.currMethodSummary = methodSummary.get(method);
        methodTaintCache.putIfAbsent(method, new HashMap<>());
        this.currTaintCache = methodTaintCache.get(method);

        // Initialize the taint summary for current method with the input entry taint (if not done yet)
        // Summary list format: idx 0: (set of taints on) base, 1: retVal, 2+: parameters
        if (!this.currMethodSummary.containsKey(entryTaint)) {
            changed = true;
            List<Set<Taint>> summary = new ArrayList<>();
            for (int i = 0; i < method.getParameterCount() + 2; i++) {
                summary.add(new HashSet<>());
            }
            this.currMethodSummary.put(entryTaint, summary);
        }

        // Get the points-to info of current method
        if (this.globalMethodInfo.containsKey(method)) {
            this.currMethodInfo = globalMethodInfo.get(method);
        } else {
            this.currMethodInfo = null;
        }
    }

    public boolean isChanged() {
        return changed;
    }

    public Set<Taint> getSources() {
        return sources;
    }

    public Set<Taint> getSinks() {
        return sinks;
    }

    public void doAnalysis() {
        logger.debug("Analyzing method {}", method);
        super.doAnalysis();
    }

    /**
     * Visit each node in CFG, use flow function to calculate out-set according to in-set.
     * For each statement, we get its corresponding uniqueStmt by calling generateUniqueStmt.
     * flowThrough() will call different methods to deal with different types of statements.
     * @param in        in-set of taints
     * @param unit      the current node to visit in CFG
     * @param out       out-set of taints
     */
    @Override
    protected void flowThrough(Set<Taint> in, Unit unit, Set<Taint> out) {
        out.clear();
        out.addAll(in);

        Stmt stmt = (Stmt) unit;
        // Get the uniqueStmt of the current statement
        UniqueStmt uniqueStmt = generateUniqueStmt(stmt);

        if (stmt instanceof AssignStmt) {
            visitAssign(in, uniqueStmt, out);
        }

        // Note that source is detected in method visitAssign()
        if (stmt instanceof InvokeStmt) {
            InvokeExpr invoke = stmt.getInvokeExpr();
            if (!sourceSinkManager.isSource(stmt)) {
                visitInvoke(in, uniqueStmt, invoke, out);
            }
        }

        if (stmt instanceof ReturnStmt || stmt instanceof ReturnVoidStmt) {
            visitReturn(in, uniqueStmt);
        }

        if (sourceSinkManager.isSink(stmt)) {
            visitSink(in, uniqueStmt);
        }
    }

    /**
     * Visit an assignment statement.
     * For each taint in in-set, if it taints left operand, it will be killed;
     *                           if it taints right operand, it will generate a new taint.
     * If the statement also contains method invoke, it will check whether source exists or call visitInvoke().
     * @param in                the in-set of taints
     * @param uniqueStmt        the current assignment statement to deal with
     * @param out               the out-set of taints
     */
    private void visitAssign(Set<Taint> in, UniqueStmt uniqueStmt, Set<Taint> out) {
        AssignStmt stmt = (AssignStmt) uniqueStmt.getStmt();
        Value leftOp = stmt.getLeftOp();
        Value rightOp = stmt.getRightOp();

        // KILL
        for (Taint t : in) {
            if (t.taints(leftOp)) {
                // remove the taint on points-to set of its base
                removeTaintOnPointsToSet(t, uniqueStmt);
                out.remove(t);
            }
        }

        // GEN
        // 1. Check the invoke method
        if (stmt.containsInvokeExpr()) {
            InvokeExpr invoke = stmt.getInvokeExpr();
            // 1. Create a new taint from source
            if (sourceSinkManager.isSource(stmt)) {
                Taint newTaint = Taint.getTaintFor(null, leftOp, uniqueStmt, method, currTaintCache);
                // Try to add the new taint into the corresponding points-to set
                taintPointsToSet(newTaint, uniqueStmt);
                sources.add(newTaint);
                out.add(newTaint);
            }
            // 2. Call visitInvoke() to continue checking taint transfer of the invoke method
            else {
                visitInvoke(in, uniqueStmt, invoke, out);
            }
        }
        // 2. Transfer a taint from right operand to left operand
        else {
            // Explicit taint flow
            for (Taint t : in) {
                if (t.taints(rightOp)) {
                    Taint newTaint;
                    // 1. Taint the whole object of left operand.
                    // If left operand is a primitive type(e.g. a or b.f where a and f are int),
                    // then we just need to taint left operand itself(a or b.f).
                    // If right operand is a reference to a field(e.g. c.f)
                    // then left operand must be a non ref type(e.g. a), so we just taint it.
                    if (leftOp.getType() instanceof PrimType || rightOp instanceof InstanceFieldRef) {
                        newTaint = Taint.getTaintFor(t, leftOp, uniqueStmt, method, currTaintCache);
                    }
                    // 2. Taint the specific field of left operand.
                    // Left operand must be a ref type.
                    // If it is a field ref(e.g. a.f), then we can taint a.f;
                    // If it is an array element ref(e.g. a[i]), then we can taint a;
                    // If it is not a ref(e.g. a), we may taint it or taint its field based last taint
                    else {
                        newTaint = Taint.getTransferredTaintFor(
                                t, leftOp, uniqueStmt, method, currTaintCache, Taint.TransferType.None);
                    }
                    // Try to add the new taint into the corresponding points-to set
                    taintPointsToSet(newTaint, uniqueStmt);
                    out.add(newTaint);
                }
            }

            // Implicit taint flow(by alias)
            if (rightOp instanceof InstanceFieldRef) {
                InstanceFieldRef rightOpRef = ((InstanceFieldRef) rightOp);
                // Get the new taint from alias
                Taint aliasTaint = getTaintOnPointsToSet(rightOpRef, uniqueStmt);
                if (aliasTaint != null && in.contains(aliasTaint)) {
                    Taint newTaint = Taint.getTaintFor(aliasTaint,
                            leftOp, uniqueStmt, method, currTaintCache);
                    // Try to add the new taint into the corresponding points-to set
                    taintPointsToSet(newTaint, uniqueStmt);
                    out.add(newTaint);
                }
            }
        }
    }

    /**
     * Visit an invoke statement.
     *
     * @param in            in-set of taints
     * @param uniqueStmt    the current invoke statement to deal with
     * @param invoke        the current invoke expression to deal with
     * @param out           out-set of taints
     */
    private void visitInvoke(Set<Taint> in, UniqueStmt uniqueStmt, InvokeExpr invoke, Set<Taint> out) {
        Stmt stmt = uniqueStmt.getStmt();
        SootMethod calleeMethod = getPreciseCalleeMethod(uniqueStmt, invoke);

        // Check if taint wrapper applies
        if (taintWrapper != null && taintWrapper.supportsCallee(calleeMethod)) {
            Set<Taint> killSet = new HashSet<>();
            Set<Taint> genSet = new HashSet<>();
            taintWrapper.genTaintsForMethodInternal(in, uniqueStmt, method, killSet, genSet, currTaintCache);
            for (Taint t : killSet) {
                // remove the taint on points-to set of its base
                removeTaintOnPointsToSet(t, uniqueStmt);
                out.remove(t);
            }
            for (Taint t : genSet) {
                // Try to add the new taint into the corresponding points-to set
                taintPointsToSet(t, uniqueStmt);
                out.add(t);
            }
            return;
        }

        // Get the base object of this invocation in caller (if applies)
        Value base = null;
        if (invoke instanceof InstanceInvokeExpr) {
            base = ((InstanceInvokeExpr) invoke).getBase();
        }

        // Get the retVal of this invocation in caller (if applies)
        Value retVal = null;
        if (stmt instanceof AssignStmt) {
            retVal = ((AssignStmt) stmt).getLeftOp();
        }

        if (!calleeMethod.hasActiveBody()) {
            logger.debug("No active body for callee {} in {}", calleeMethod, method);
            return;
        }

        Body calleeBody = calleeMethod.getActiveBody();

        // The set of taints that are generated and killed
        Set<Taint> killSet = new HashSet<>();
        Set<Taint> genSet = new HashSet<>();

        // Get this object in callee (if exists)
        Value calleeThisLocal = null;
        if (invoke instanceof InstanceInvokeExpr) {
            calleeThisLocal = calleeBody.getThisLocal();
        }

        // Initialize methodSummary and methodTaintCache for callee (if not done yet)
        methodSummary.putIfAbsent(calleeMethod, new HashMap<>());
        Map<Taint, List<Set<Taint>>> calleeSummary = methodSummary.get(calleeMethod);
        methodTaintCache.putIfAbsent(calleeMethod, new HashMap<>());
        Map<Taint, Taint> calleeTaintCache = methodTaintCache.get(calleeMethod);

        // Initialize the empty taint summary for callee (if not done yet)
        // Summary list format: idx 0: (set of taints on) base, 1: retVal, 2+: parameters
        if (!calleeSummary.containsKey(Taint.getEmptyTaint())) {
            changed = true;
            List<Set<Taint>> emptyTaintSummary = new ArrayList<>();
            for (int i = 0; i < calleeMethod.getParameterCount() + 2; i++) {
                emptyTaintSummary.add(new HashSet<>());
            }
            calleeSummary.put(Taint.getEmptyTaint(), emptyTaintSummary);
        }

        // Initialize the summary for this invocation by elements copied from the empty taint summary
        List<Set<Taint>> summary = new ArrayList<>();
        for (Set<Taint> taints : calleeSummary.get(Taint.getEmptyTaint())) {
            Set<Taint> newTaints = new HashSet<>();
            newTaints.addAll(taints);
            summary.add(newTaints);
        }

        // Compute KILL and gather summary info for this invocation
        for (Taint t : in) {
            // Process base object
            if (base != null && t.taints(base)) {
                killSet.add(t);
                genCalleeEntryTaints(t, calleeThisLocal, uniqueStmt, calleeSummary, calleeTaintCache, summary, calleeMethod);
            }

            // Process parameters
            for (int i = 0; i < invoke.getArgCount(); i++) {
                Value arg = invoke.getArg(i);
                if (t.taints(arg)) {
                    // Check if the param is basic type (we should pass on the taint in that case)
                    if (!(arg.getType() instanceof PrimType)) {
                        killSet.add(t);
                    }
                    Local calleeParam = calleeBody.getParameterLocal(i);
                    genCalleeEntryTaints(t, calleeParam, uniqueStmt, calleeSummary, calleeTaintCache, summary, calleeMethod);
                }
            }
        }

        // Compute GEN from the gathered summary info
        // Process base object
        if (base != null) {
            Set<Taint> baseTaints = summary.get(0);
            genSet.addAll(getTaintsFromInvokeSummary(baseTaints, base, uniqueStmt));
        }

        // Process return value
        if (retVal != null) {
            Set<Taint> retTaints = summary.get(1);
            genSet.addAll(getTaintsFromInvokeSummary(retTaints, retVal, uniqueStmt));
        }

        // Process parameters
        for (int i = 0; i < invoke.getArgCount(); i++) {
            Value arg = invoke.getArg(i);
            Set<Taint> argTaints = summary.get(2 + i);
            genSet.addAll(getTaintsFromInvokeSummary(argTaints, arg, uniqueStmt));
        }

        // KILL
        for (Taint t : killSet) {
            // remove the taint on points-to set of its base
            removeTaintOnPointsToSet(t, uniqueStmt);
            out.remove(t);
        }

        // GEN the UNION of all gen sets
        for (Taint t : genSet) {
            // We have added the new taint into the corresponding points-to set
            // in method getTaintsFromInvokeSummary().
            out.add(t);
        }
    }

    /**
     * Generate the entry taint for callee.
     * @param t                 the taint in caller(as a context info)
     * @param calleeVal         the value which the taint is on
     * @param uniqueStmt        the statement context of the taint
     * @param calleeSummary     the summary of taints for callee
     * @param calleeTaintCache  the cache that stores the taint in callee
     * @param summary           the taint set summary of callee taint
     * @param callee            the callee method
     */
    private void genCalleeEntryTaints(Taint t, Value calleeVal, UniqueStmt uniqueStmt,
                                      Map<Taint, List<Set<Taint>>> calleeSummary,
                                      Map<Taint, Taint> calleeTaintCache,
                                      List<Set<Taint>> summary,
                                      SootMethod callee) {
        // Generate caller taint at call site
        Taint callerTaint = Taint.getTransferredTaintFor(
                t, t.getPlainValue(), uniqueStmt, method, currTaintCache, Taint.TransferType.Call);
        // Try to add the new taint into the corresponding points-to set
        taintPointsToSet(callerTaint, uniqueStmt);

        // Send caller taint to callee
        PhantomIdentityStmt phantomIdentityStmt = PhantomIdentityStmt.getInstance(callee);
        UniqueStmt uniquePhantomIdentityStmt = generateUniqueStmt(phantomIdentityStmt);
        Taint calleeTaint = Taint.getTransferredTaintFor(
                callerTaint, calleeVal, uniquePhantomIdentityStmt, callee, calleeTaintCache);

        // Try to add the new taint into the corresponding points-to set
        taintPointsToSet(calleeTaint, uniqueStmt);

        // Receive callee taint summary for the sent caller taint
        if (calleeSummary.containsKey(calleeTaint)) {
            List<Set<Taint>> lst = calleeSummary.get(calleeTaint);
            for (int i = 0; i < lst.size(); i++) {
                summary.get(i).addAll(lst.get(i));
            }
        } else {
            // Generate new summary entry for the callee taint
            changed = true;
            List<Set<Taint>> newSummary = new ArrayList<>();
            for (int i = 0; i < callee.getParameterCount() + 2; i++) {
                newSummary.add(new HashSet<>());
            }
            calleeSummary.put(calleeTaint, newSummary);
        }
    }

    /**
     * Transfers taints from the summary of callee to the return site of caller.
     * @param taints        the set of taints in callee
     * @param callerVal     the value at return site in caller
     * @param uniqueStmt    the statement at return site in caller
     * @return              the set of taints at return site in caller
     */
    private Set<Taint> getTaintsFromInvokeSummary(Set<Taint> taints, Value callerVal, UniqueStmt uniqueStmt) {
        Set<Taint> out = new HashSet<>();
        if (callerVal instanceof NullConstant) {
            return out;
        }
        for (Taint t : taints) {
            Taint callerTaint = Taint.getTransferredTaintFor(
                    t, callerVal, uniqueStmt, method, currTaintCache, Taint.TransferType.Return);
            // Try to add the new taint into the corresponding points-to set
            taintPointsToSet(callerTaint, uniqueStmt);
            out.add(callerTaint);
        }
        return out;
    }

    /**
     * Visit a return statement.
     * It transfers taints to this object, return value and parameters at return site(if possible).
     * @param in            in-set of taints
     * @param uniqueStmt    the return statement to deal with
     */
    private void visitReturn(Set<Taint> in, UniqueStmt uniqueStmt) {
        Stmt stmt = uniqueStmt.getStmt();
        // Get the local representing @this (if exists)
        Local thiz = null;
        if (!body.getMethod().isStatic()) {
            thiz = body.getThisLocal();
        }

        // Get return value (if exists)
        Value retVal = null;
        if (stmt instanceof ReturnStmt) {
            retVal = ((ReturnStmt) stmt).getOp();
        }

        // Get the list of Locals representing the parameters (on LHS of IdentityStmt)
        List<Local> paramLocals = body.getParameterLocals();
        // The discovered taints in current context of current method
        List<Set<Taint>> summary = currMethodSummary.get(entryTaint);

        // Do taint transfer
        // If a taint is successfully transferred and added into summary,
        // flag variable `changed` will be set as true
        for (Taint t : in) {
            // Check if t taints base object
            if (thiz != null && t.taints(thiz)) {
                UniqueStmt uniquePhantomRetStmt = generateUniqueStmt(phantomRetStmt);
                Taint newTaint = Taint.getTransferredTaintFor(
                        t, t.getPlainValue(), uniquePhantomRetStmt, method, currTaintCache);
                // Try to add the new taint into the corresponding points-to set
                taintPointsToSet(newTaint, uniqueStmt);

                changed |= summary.get(0).add(newTaint);
            }

            // Check if t taints return value
            if (retVal != null && t.taints(retVal)) {
                UniqueStmt uniquePhantomRetStmt = generateUniqueStmt(phantomRetStmt);
                Taint newTaint = Taint.getTransferredTaintFor(
                        t, t.getPlainValue(), uniquePhantomRetStmt, method, currTaintCache);
                // Try to add the new taint into the corresponding points-to set
                taintPointsToSet(newTaint, uniqueStmt);

                changed |= summary.get(1).add(newTaint);
            }

            // Check if t taints object-type parameters
            for (int i = 0; i < paramLocals.size(); i++) {
                Local paramLocal = paramLocals.get(i);
                // Check if the param is basic type (we should not taint them in that case)
                if (!(paramLocal.getType() instanceof PrimType) && t.taints(paramLocal)) {
                    UniqueStmt uniquePhantomRetStmt = generateUniqueStmt(phantomRetStmt);
                    Taint newTaint = Taint.getTransferredTaintFor(
                            t, t.getPlainValue(), uniquePhantomRetStmt, method, currTaintCache);
                    // Try to add the new taint into the corresponding points-to set
                    taintPointsToSet(newTaint, uniqueStmt);

                    changed |= summary.get(2 + i).add(newTaint);
                }
            }
        }
    }

    /**
     * Visit a sink, which is a call to an application method.
     * If the base or parameters are tainted by a taint from in-set,
     * then we can transfer a taint to the base or parameters
     * and regard this taint as reaching the sink.
     * @param in            the in-set of taint
     * @param uniqueStmt    the current statement that contains sink
     */
    private void visitSink(Set<Taint> in, UniqueStmt uniqueStmt) {
        Stmt stmt = uniqueStmt.getStmt();
        if (!stmt.containsInvokeExpr()) return;
        InvokeExpr invoke = stmt.getInvokeExpr();
        SootMethod sinkMethod = getPreciseCalleeMethod(uniqueStmt, invoke);

        Value base = null;
        if (invoke instanceof InstanceInvokeExpr) {
            base = ((InstanceInvokeExpr) invoke).getBase();
        }

        // for debugging
        // collecting taints at sink call site
        Set<Taint> definiteTaints = new HashSet<>();
        Set<Taint> possibleTaints = new HashSet<>();
        Set<Taint> unknownTaints = new HashSet<>();
        Set<Taint> impossibleTaints = new HashSet<>();

        for (Taint t : in) {
            for (int i = -1; i < invoke.getArgCount(); i++) {
                // callerValue can be base or parameter(at caller side),
                // and it is a simple ref.
                Value callerValue;
                if (i == -1) {
                    callerValue = base;
                } else {
                    callerValue = invoke.getArg(i);
                }
                // process taint and value based on how precise that taint taints value
                if (callerValue != null && t.taints(callerValue)) {
                    // Check whether t taints value precisely
                    // 1. t taints a reference instance whose base is value
                    if (t.taintsField(callerValue)) {
                        JInstanceFieldRef callerValueRef = new JInstanceFieldRef(callerValue,
                                t.getField().makeRef());
                        FieldUseType useType = fieldUseChecker.checkUse(callerValueRef, sinkMethod, i);

                        if (useType == FieldUseType.Must) {
                            definiteTaints.add(t);
                        } else if (useType == FieldUseType.May) {
                            possibleTaints.add(t);
                        } else if (useType == FieldUseType.Unknown) {
                            unknownTaints.add(t);
                        }
                        else {
                            impossibleTaints.add(t);
                        }
                    }
                    // 2. t taints value precisely
                    else {
                        definiteTaints.add(t);
                    }
                }
            }
        }

        // Propagate taints into sink
        for (Taint taint : definiteTaints) {
            Taint sinkTaint = Taint.getTransferredTaintFor(
                    taint, taint.getPlainValue(), uniqueStmt, method, currTaintCache);
            // Try to add the new taint into the corresponding points-to set
            taintPointsToSet(sinkTaint, uniqueStmt);

            sinkTaint.setSink();
            sinks.add(sinkTaint);
        }

        // For debug, check the possible and impossible sink taints
        for (Taint taint : possibleTaints) {
            Taint sinkTaint = Taint.getTransferredTaintFor(
                    taint, taint.getPlainValue(), uniqueStmt, method, currTaintCache);
            taint.removeSuccessor(sinkTaint);
            mayUseSinks.add(sinkTaint);
        }

        for (Taint taint : unknownTaints) {
            Taint sinkTaint = Taint.getTransferredTaintFor(
                    taint, taint.getPlainValue(), uniqueStmt, method, currTaintCache);
            taint.removeSuccessor(sinkTaint);
            unknownSinks.add(sinkTaint);
        }

        for (Taint taint : impossibleTaints) {
            Taint sinkTaint = Taint.getTransferredTaintFor(
                    taint, taint.getPlainValue(), uniqueStmt, method, currTaintCache);
            taint.removeSuccessor(sinkTaint);
            mustNotUsedSinks.add(sinkTaint);
        }
    }

    /**
     * Initialize the in-set of taints for each node except the entry node.
     * @return      the initial in-set of taints
     */
    @Override
    protected Set<Taint> newInitialFlow() {
        return new HashSet<>();
    }

    /**
     * Initialize the in-set of taints at the entry node,
     * which contains entryTaint passed to this TaintFlowAnalysis object.
     * @return      the in-set of taints
     */
    @Override
    protected Set<Taint> entryInitialFlow() {
        Set<Taint> entryTaints = new HashSet<>();
        if (!entryTaint.isEmpty()) {
            entryTaints.add(entryTaint);
        }
        return entryTaints;
    }

    /**
     * Merge method as a meet operator, which is set Union.
     * @param in1       one in-set of taints for a node
     * @param in2       another in-set of taints for a node
     * @param out       out-set of taints for a node
     */
    @Override
    protected void merge(Set<Taint> in1, Set<Taint> in2, Set<Taint> out) {
        out.clear();
        out.addAll(in1);
        out.addAll(in2);
    }

    /**
     * Copy source to destination.
     * @param source    source taint set
     * @param dest      destination taint set
     */
    @Override
    protected void copy(Set<Taint> source, Set<Taint> dest) {
        dest.clear();
        dest.addAll(source);
    }

    /**
     * Generate UniqueStmt of stmt with count based on stmtStrCounter, countedStmtCache and uniqueStmtCache.
     * Basically, we will get the count id of that statement from stmtStrCounter and countedStmtCache.
     * Then, we will generate the UniqueStmt with the help of uniqueStmtCache.
     * @param stmt      the current statement
     * @return          the UniqueStmt of (stmt, count)
     */
    private UniqueStmt generateUniqueStmt(Stmt stmt) {

        // Set the original default count as -1
        Integer count = -1;
        // The string format of that statement
        String stmtStr = stmt.toString();

        // Check whether the stmt has been counted
        // 1. The stmt has been counted, we don't need to count it again
        if (countedStmtCache.containsKey(stmt)) {
            count = countedStmtCache.get(stmt);
        }
        // 2. The stmt hasn't been counted, find the count in stmtStrCounter
        else {
            // Check whether the string of that statement has been counted
            // 1. The string of that statement has been counted,
            // so we get the new count by simply incrementing it
            // and record it into stmtStrCounter
            if (stmtStrCounter.containsKey(stmtStr)) {
                count = stmtStrCounter.get(stmtStr);
                count = count + 1;
                stmtStrCounter.put(stmtStr, count);
            }
            // 2. The string of that statement hasn't been counted,
            // so we set the new count as 1
            // and record it into stmtCounter
            else {
                count = 1;
                stmtStrCounter.put(stmtStr, count);
            }
            // Store the count id of that statement into countedStmtCache
            countedStmtCache.put(stmt, count);
        }

        // Generate a new UniqueStmt
        UniqueStmt uniqueStmt = new UniqueStmt(stmt, count);

        // The uniqueStmt has been stored in uniqueStmtCache, just get it from uniqueStmtCache
        if (uniqueStmtCache.containsKey(uniqueStmt)) {
            uniqueStmt = uniqueStmtCache.get(uniqueStmt);
        }
        // The uniqueStmt has been stored in uniqueStmtCache, just put it into uniqueStmtCache
        else {
            uniqueStmtCache.put(uniqueStmt, uniqueStmt);
        }

        return uniqueStmt;
    }

    /**
     * Add a new taint into the points-to set of its plainValue.
     * The new taint may be added only if it is an instanceRef.
     * @param newTaint          the new taint to be added
     * @param uniqueStmt        the current program point,
     *                          in order to get the points-to set from currMethodInfo
     */
    private void taintPointsToSet(Taint newTaint, UniqueStmt uniqueStmt) {
        // The plain value(base) and field of the new taint
        Value newTaintPlainValue = newTaint.getPlainValue();
        SootField newTaintField = newTaint.getField();

        // No alias problem for non instanceRef
        if (newTaintField == null) {
            return;
        }

        // Add newTaint into the points-to set of newTaintBase
        if (currMethodInfo != null) {
            Map<UniqueStmt, Map<Value, PointsToSet>> programStates
                    = currMethodInfo.getProgramStates();
            if (programStates != null) {
                Map<Value, PointsToSet> programState = programStates.get(uniqueStmt);
                if (programState != null) {
                    PointsToSet basePts = programState.get(newTaintPlainValue);
                    if (basePts instanceof ObjRefPointsToSet) {
                        ((ObjRefPointsToSet) basePts).addTaint(newTaint);
                    }
                }
            }
        }
    }

    /**
     * Remove the taint on the points-to set of its plainValue.
     * @param taint          the taint to be removed
     * @param uniqueStmt        the current program point,
     *                          in order to get the points-to set from currMethodInfo
     */
    private void removeTaintOnPointsToSet(Taint taint, UniqueStmt uniqueStmt) {
        // The plain value(base) and field of the taint
        Value newTaintPlainValue = taint.getPlainValue();
        SootField newTaintField = taint.getField();

        // No alias problem for non instanceRef
        if (newTaintField == null) {
            return;
        }

        // remove the taint on the points-to set of newTaintBase
        if (currMethodInfo != null) {
            Map<UniqueStmt, Map<Value, PointsToSet>> programStates
                    = currMethodInfo.getProgramStates();
            if (programStates != null) {
                Map<Value, PointsToSet> programState = programStates.get(uniqueStmt);
                if (programState != null) {
                    PointsToSet basePts = programState.get(newTaintPlainValue);
                    if (basePts instanceof ObjRefPointsToSet) {
                        ((ObjRefPointsToSet) basePts).removeTaint(taint);
                    }
                }
            }
        }
    }

    /**
     * get the taint on the points-to set of the base of an instanceRef .
     * @param ref               the reference to an instance field
     * @param uniqueStmt        the current program point,
     *                          in order to get the points-to set from currMethodInfo
     */
    private Taint getTaintOnPointsToSet(InstanceFieldRef ref, UniqueStmt uniqueStmt) {
        // The plain value(base) and field of the taint
        Value base = ref.getBase();
        SootField field = ref.getField();

        // No alias problem for non instanceRef
        if (field == null) {
            return null;
        }

        // Get the taint on the points-to set of the field
        Taint taint = null;
        if (currMethodInfo != null) {
            Map<UniqueStmt, Map<Value, PointsToSet>> programStates
                    = currMethodInfo.getProgramStates();
            if (programStates != null) {
                Map<Value, PointsToSet> programState = programStates.get(uniqueStmt);
                if (programState != null) {
                    PointsToSet basePts = programState.get(base);
                    if (basePts instanceof ObjRefPointsToSet) {
                        taint = ((ObjRefPointsToSet) basePts).getTaint(field);
                    }
                }
            }
        }
        return taint;
    }

    /** Check whether the given method is an init method */
    private boolean isInitMethod(SootMethod method) {
        return method.getName().equals("<init>");
    }

    /** Check whether the given method is an Object init method */
    private boolean isObjectInitMethod(SootMethod method) {
        return method.getSignature().equals("<java.lang.Object: void <init>()>");
    }

    /**
     * Get the precise caller method of the invoke statement
     * @param uniqueStmt        the current invoke statement
     * @param invoke            the current invoke expression
     * @return                  the precise callee method
     */
    private SootMethod getPreciseCalleeMethod(UniqueStmt uniqueStmt, InvokeExpr invoke) {
        Stmt stmt = uniqueStmt.getStmt();
        // Get the base type of this invocation in caller (if applies)
        Value base = null;
        RefType baseType = null;
        if (invoke instanceof InstanceInvokeExpr) {
            base = ((InstanceInvokeExpr) invoke).getBase();
        }
        if (base != null) {
            // Get the points-to set of base to get its dynamic type
            PointsToSet basePts = null;
            if (currMethodInfo != null) {
                Map<UniqueStmt, Map<Value, PointsToSet>> currProgramStates
                        = currMethodInfo.getProgramStates();
                if (currProgramStates != null) {
                    Map<Value, PointsToSet> programState = currProgramStates.get(uniqueStmt);
                    if (programState != null) {
                        basePts = programState.get(base);
                    }
                }
            }
            // Get dynamic type of base(if possible)
            if (basePts != null && basePts.getLocation() != null &&
                    basePts.getLocation().getType() instanceof RefType) {
                baseType = (RefType) basePts.getLocation().getType();
            }
            // Or we can get static declared type of base
            else if (base.getType() instanceof RefType) {
                baseType = (RefType) base.getType();
            }
        }

        // Get callee method
        // Use call graph to cope with polymorphism
        SootMethod calleeMethod = invoke.getMethod();
        // For non init method, we should find its precise callee
        if (!isInitMethod(calleeMethod) && cg != null) {
            for (Iterator<Edge> it = cg.edgesOutOf(stmt); it.hasNext(); ) {
                Edge edge = it.next();
                SootMethod sm = edge.tgt();
                if (baseType != null && baseType.getSootClass() == sm.getDeclaringClass()) {
                    calleeMethod = sm;
                    break;
                }
            }
        }
        return calleeMethod;
    }


}