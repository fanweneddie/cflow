package taintAnalysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;
import taintAnalysis.sourceSinkManager.ISourceSinkManager;
import taintAnalysis.taintWrapper.ITaintWrapper;
import taintAnalysis.utility.PhantomIdentityStmt;
import taintAnalysis.utility.PhantomRetStmt;

import java.util.*;

import static assertion.Assert.assertNotNull;

/**
 * The forward analysis framework for taint analysis
 * This framework deals with intra-procedural analysis
 */
public class TaintFlowAnalysis extends ForwardFlowAnalysis<Unit, Set<Taint>> {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    
    private static final CallGraph cg = Scene.v().hasCallGraph() ? Scene.v().getCallGraph() : null;

    private boolean changed = false;
    private final Body body;
    private final SootMethod method;
    private final ISourceSinkManager sourceSinkManager;
    private final ITaintWrapper taintWrapper;
    private final Taint entryTaint;
    private final Map<SootMethod, Map<Taint, List<Set<Taint>>>> methodSummary;
    private final Map<Taint, List<Set<Taint>>> currMethodSummary;
    private final Map<SootMethod, Map<Taint, Taint>> methodTaintCache;
    private final Map<Taint, Taint> currTaintCache;
    private final PhantomRetStmt phantomRetStmt;
    private final Set<Taint> sources;
    private final Set<Taint> sinks;
    // Three data structures below are used for the initialization of uniqueStmt
    // stores the overall count number of string of each statement
    private final Map<String, Integer> stmtStrCounter;
    // stores the count id of string of each statement
    private final Map<Stmt, Integer> countedStmtCache;
    // stores the generated UniqueStmt(in order to reduce repetitious object generation)
    private final Map<UniqueStmt, UniqueStmt> uniqueStmtCache;

    public TaintFlowAnalysis(Body body, ISourceSinkManager sourceSinkManager) {
        this(body, sourceSinkManager, Taint.getEmptyTaint(), new HashMap<>(), new HashMap<>(), null,
                null, null, null);
    }

    /**
     * This constructor ignores the initialization of stmtStrCounter, countedStmtCache and uniqueStmtCache
     * @param body
     * @param sourceSinkManager
     * @param entryTaint
     * @param methodSummary
     * @param methodTaintCache
     * @param taintWrapper
     */
    public TaintFlowAnalysis(Body body,
                             ISourceSinkManager sourceSinkManager,
                             Taint entryTaint,
                             Map<SootMethod, Map<Taint, List<Set<Taint>>>> methodSummary,
                             Map<SootMethod, Map<Taint, Taint>> methodTaintCache,
                             ITaintWrapper taintWrapper) {
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

        this.stmtStrCounter = null;
        this.countedStmtCache = null;
        this.uniqueStmtCache = null;

        // Sanity check
        assertNotNull(body);
        assertNotNull(sourceSinkManager);
        assertNotNull(entryTaint);
        assertNotNull(methodSummary);
        assertNotNull(methodTaintCache);

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
    }

    /**
     * The complete constructor
     * @param body
     * @param sourceSinkManager
     * @param entryTaint
     * @param methodSummary
     * @param methodTaintCache
     * @param taintWrapper
     * @param stmtStrCounter
     * @param countedStmtCache
     * @param uniqueStmtCache
     */
    public TaintFlowAnalysis(Body body,
                             ISourceSinkManager sourceSinkManager,
                             Taint entryTaint,
                             Map<SootMethod, Map<Taint, List<Set<Taint>>>> methodSummary,
                             Map<SootMethod, Map<Taint, Taint>> methodTaintCache,
                             ITaintWrapper taintWrapper, Map<String, Integer> stmtStrCounter,
                             Map<Stmt, Integer> countedStmtCache, Map<UniqueStmt, UniqueStmt> uniqueStmtCache) {
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
        this.stmtStrCounter = stmtStrCounter;
        this.countedStmtCache = countedStmtCache;
        this.uniqueStmtCache = uniqueStmtCache;

        // Sanity check
        assertNotNull(body);
        assertNotNull(sourceSinkManager);
        assertNotNull(entryTaint);
        assertNotNull(methodSummary);
        assertNotNull(methodTaintCache);

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
     * Visit each node in CFG, use flow function to calculate out-set according to in-set
     * For each statement, we get its corresponding uniqueStmt by calling generateUniqueStmt
     * flowThrough() will call different methods to deal with different types of statements
     * @param in        in-set of taints
     * @param unit      the current node to visit in CFG
     * @param out       out-set of taints
     */
    @Override
    protected void flowThrough(Set<Taint> in, Unit unit, Set<Taint> out) {
        out.clear();
        out.addAll(in);

        Stmt stmt = (Stmt) unit;
        // get the uniqueStmt of the current statement
        UniqueStmt uniqueStmt = generateUniqueStmt(stmt);

        if (stmt instanceof AssignStmt) {
            visitAssign(in, uniqueStmt, out);
        }

        // for implicit taint flow(by control flow)
        if (stmt instanceof IfStmt) {
            visitIf(in, uniqueStmt, out);
        }

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
     * Visit an assignment statement
     * for each taint in in-set, if it taints left-operator, it will be killed;
     *                           if it taints right-operator, it will generate a new taint
     * if the statement also contains method invoke, it will check whether source exists or calls visitInvoke()
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
                out.remove(t);
            }
        }

        // GEN
        if (stmt.containsInvokeExpr()) {
            InvokeExpr invoke = stmt.getInvokeExpr();
            // get a new taint from source
            if (sourceSinkManager.isSource(stmt)) {
                Taint newTaint = Taint.getTaintFor(null, leftOp, uniqueStmt, method, currTaintCache);
                sources.add(newTaint);
                out.add(newTaint);
            } else {
                visitInvoke(in, uniqueStmt, invoke, out);
            }
        } else {
            for (Taint t : in) {
                if (t.taints(rightOp)) {
                    Taint newTaint;
                    // why???
                    if (leftOp.getType() instanceof PrimType || rightOp instanceof InstanceFieldRef) {
                        newTaint = Taint.getTaintFor(t, leftOp, uniqueStmt, method, currTaintCache);
                    }
                    // get a new taint whose transfer type is assign
                    else {
                        newTaint = Taint.getTransferredTaintFor(
                                t, leftOp, uniqueStmt, method, currTaintCache, Taint.TransferType.None);
                    }
                    out.add(newTaint);
                }
            }
        }
    }

    /**
     * Visit an invoke statement
     * @param in            in-set of taints
     * @param uniqueStmt    the current invoke statement to deal with
     * @param invoke        the current invoke expression to deal with
     * @param out           out-set of taints
     */
    private void visitInvoke(Set<Taint> in, UniqueStmt uniqueStmt, InvokeExpr invoke, Set<Taint> out) {
        Stmt stmt = uniqueStmt.getStmt();
        SootMethod calleeMethod = invoke.getMethod();
        assertNotNull(calleeMethod);

        // Check if taint wrapper applies
        if (taintWrapper != null && taintWrapper.supportsCallee(calleeMethod)) {
            Set<Taint> killSet = new HashSet<>();
            Set<Taint> genSet = new HashSet<>();
            taintWrapper.genTaintsForMethodInternal(in, uniqueStmt, method, killSet, genSet, currTaintCache);
            for (Taint t : killSet) {
                out.remove(t);
            }
            for (Taint t : genSet) {
                out.add(t);
            }
            return;
        }

        // Get all possible callees for this call site
        List<SootMethod> methods = new ArrayList<>();
        methods.add(calleeMethod);
        if (cg != null) {
            for (Iterator<Edge> it = cg.edgesOutOf(stmt); it.hasNext(); ) {
                Edge edge = it.next();
                SootMethod sm = edge.tgt();
                if (calleeMethod.getName().equals(sm.getName())) {
                    methods.add(sm);
                }
            }
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

        // Compute KILL and GEN
        List<Set<Taint>> killSets = new ArrayList<>();
        List<Set<Taint>> genSets = new ArrayList<>();
        for (SootMethod callee : methods) {
            if (!callee.hasActiveBody()) {
                logger.debug("No active body for callee {} in {}", callee, method);
                continue;
            }
            Body calleeBody = callee.getActiveBody();

            Set<Taint> killSet = new HashSet<>();
            Set<Taint> genSet = new HashSet<>();
            killSets.add(killSet);
            genSets.add(genSet);

            // Get this object in callee (if exists)
            Value calleeThisLocal = null;
            if (invoke instanceof InstanceInvokeExpr) {
                calleeThisLocal = calleeBody.getThisLocal();
            }

            // Initialize methodSummary and methodTaintCache for callee (if not done yet)
            methodSummary.putIfAbsent(callee, new HashMap<>());
            Map<Taint, List<Set<Taint>>> calleeSummary = methodSummary.get(callee);
            methodTaintCache.putIfAbsent(callee, new HashMap<>());
            Map<Taint, Taint> calleeTaintCache = methodTaintCache.get(callee);

            // Initialize the empty taint summary for callee (if not done yet)
            // Summary list format: idx 0: (set of taints on) base, 1: retVal, 2+: parameters
            if (!calleeSummary.containsKey(Taint.getEmptyTaint())) {
                changed = true;
                List<Set<Taint>> emptyTaintSummary = new ArrayList<>();
                for (int i = 0; i < callee.getParameterCount() + 2; i++) {
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
                    genCalleeEntryTaints(t, calleeThisLocal, uniqueStmt, calleeSummary,
                            calleeTaintCache, summary, callee);
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
                        genCalleeEntryTaints(t, calleeParam, uniqueStmt, calleeSummary,
                                calleeTaintCache, summary, callee);
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
        }

        // KILL the INTERSECTION of all kill sets
        Set<Taint> killSet = new HashSet<>();
        for (int i = 0; i < killSets.size(); i++) {
            if (i == 0) {
                killSet.addAll(killSets.get(0));
            } else {
                killSet.retainAll(killSets.get(i));
            }
        }
        for (Taint t : killSet) {
            out.remove(t);
        }

        // GEN the UNION of all gen sets
        Set<Taint> genSet = new HashSet<>();
        for (Set<Taint> s : genSets) {
            genSet.addAll(s);
        }
        for (Taint t : genSet) {
            out.add(t);
        }
    }

    /**
     * Generate the entry taint for callee
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

        // Send caller taint to callee
        PhantomIdentityStmt phantomIdentityStmt = PhantomIdentityStmt.getInstance(callee);
        UniqueStmt uniquePhantomIdentityStmt = generateUniqueStmt(phantomIdentityStmt);
        Taint calleeTaint = Taint.getTransferredTaintFor(
                callerTaint, calleeVal, uniquePhantomIdentityStmt, callee, calleeTaintCache);

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
     * Get the taints from the summary of callee to the return site of caller
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
            out.add(callerTaint);
        }
        return out;
    }

    /**
     * Visit a return statement
     * @param in            in-set of taints
     * @param uniqueStmt    the current return statement to deal with
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

        List<Set<Taint>> summary = currMethodSummary.get(entryTaint);
        for (Taint t : in) {
            // Check if t taints base object
            if (thiz != null && t.taints(thiz)) {
                UniqueStmt uniquePhantomRetStmt = generateUniqueStmt(phantomRetStmt);
                Taint newTaint = Taint.getTransferredTaintFor(
                        t, t.getPlainValue(), uniquePhantomRetStmt, method, currTaintCache);
                changed |= summary.get(0).add(newTaint);
            }

            // Check if t taints return value
            if (retVal != null && t.taints(retVal)) {
                UniqueStmt uniquePhantomRetStmt = generateUniqueStmt(phantomRetStmt);
                Taint newTaint = Taint.getTransferredTaintFor(
                        t, t.getPlainValue(), uniquePhantomRetStmt, method, currTaintCache);
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
                    changed |= summary.get(2 + i).add(newTaint);
                }
            }
        }
    }

    /**
     * Visit a sink
     * @param in            the in-set of taint
     * @param uniqueStmt    the current statement that contains sink
     */
    private void visitSink(Set<Taint> in, UniqueStmt uniqueStmt) {
        Stmt stmt = uniqueStmt.getStmt();
        if (!stmt.containsInvokeExpr()) return;
        InvokeExpr invoke = stmt.getInvokeExpr();

        Value base = null;
        if (invoke instanceof InstanceInvokeExpr) {
            base = ((InstanceInvokeExpr) invoke).getBase();
        }

        for (Taint t : in) {
            // Process base object
            if (base != null && t.taints(base)) {
                Taint sinkTaint = Taint.getTransferredTaintFor(
                        t, t.getPlainValue(), uniqueStmt, method, currTaintCache);
                sinkTaint.setSink();
                sinks.add(sinkTaint);
            }

            // Process parameters
            for (int i = 0; i < invoke.getArgCount(); i++) {
                Value arg = invoke.getArg(i);
                if (t.taints(arg)) {
                    Taint sinkTaint = Taint.getTransferredTaintFor(
                            t, t.getPlainValue(), uniqueStmt, method, currTaintCache);
                    sinkTaint.setSink();
                    sinks.add(sinkTaint);
                }
            }
        }
    }

    /**
     * Visit an if statement
     * @param in
     * @param uniqueStmt
     * @param out
     * to be continued...
     */
    private void visitIf(Set<Taint> in, UniqueStmt uniqueStmt, Set<Taint> out) {

    }

    @Override
    protected Set<Taint> newInitialFlow() {
        return new HashSet<>();
    }

    @Override
    protected Set<Taint> entryInitialFlow() {
        Set<Taint> entryTaints = new HashSet<>();
        if (!entryTaint.isEmpty()) {
            entryTaints.add(entryTaint);
        }
        return entryTaints;
    }

    @Override
    protected void merge(Set<Taint> in1, Set<Taint> in2, Set<Taint> out) {
        out.clear();
        out.addAll(in1);
        out.addAll(in2);
    }

    @Override
    protected void copy(Set<Taint> source, Set<Taint> dest) {
        dest.clear();
        dest.addAll(source);
    }

    /**
     * Generate UniqueStmt of stmt with count based on stmtStrCounter, countedStmtCache and uniqueStmtCache
     * Basically, we will get the count id of that statement from stmtStrCounter and countedStmtCache
     * Then, we will generate the UniqueStmt with the help of uniqueStmtCache
     * @param stmt      the current statement
     * @return          the UniqueStmt of (stmt, count)
     */
    private UniqueStmt generateUniqueStmt(Stmt stmt) {

        // set the original default count as -1
        Integer count = -1;
        // the string format of that statement
        String stmtStr = stmt.toString();

        // that stmt has been counted, we don't need to count it again
        if (countedStmtCache.containsKey(stmt)) {
            count = countedStmtCache.get(stmt);
        }
        // the stmt hasn't been counted, find the count in stmtStrCounter
        else {
            // the string of that statement has been counted,
            // so we get the new count by simply incrementing it
            // and record it into stmtStrCounter
            if (stmtStrCounter.containsKey(stmtStr)) {
                count = stmtStrCounter.get(stmtStr);
                count = count + 1;
                stmtStrCounter.put(stmtStr, count);
            }
            // the string of that statement hasn't been counted
            // so we set the new count as 1
            // and record it into stmtCounter
            else {
                count = 1;
                stmtStrCounter.put(stmtStr, count);
            }
            // store the count id of that statement into countedStmtCache
            countedStmtCache.put(stmt, count);
        }

        // generate a new UniqueStmt
        UniqueStmt uniqueStmt = new UniqueStmt(stmt, count);

        // the uniqueStmt has been stored in uniqueStmtCache, just get it from uniqueStmtCache
        if (uniqueStmtCache.containsKey(uniqueStmt)) {
            uniqueStmt = uniqueStmtCache.get(uniqueStmt);
        }
        // the uniqueStmt has been stored in uniqueStmtCache, just put it into uniqueStmtCache
        else {
            uniqueStmtCache.put(uniqueStmt, uniqueStmt);
        }

        return uniqueStmt;
    }

}
