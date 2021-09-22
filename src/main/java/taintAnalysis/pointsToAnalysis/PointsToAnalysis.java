package taintAnalysis.pointsToAnalysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.JNewExpr;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;

import taintAnalysis.UniqueStmt;
import taintAnalysis.utility.PhantomRetStmt;

import java.util.*;

import static assertion.Assert.assertNotNull;

/**
 * The framework of a context-, flow-, base-sensitive inter-procedural points-to analysis
 * (by calling intra-procedural analysis multiple times to simulate inter-procedural analysis)
 * lattice: a map of <variable/object, set<AbstractLoc>>
 * direction: forward
 * meet operator: map union
 * transfer function: strong update
 */
public class PointsToAnalysis extends ForwardFlowAnalysis<Unit, Map<Value, Set<AbstractLoc>>> {

    // here I copy the fields in TaintFlowAnalysis since their framework is similar
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private static final CallGraph cg = Scene.v().hasCallGraph() ? Scene.v().getCallGraph() : null;

    private boolean changed = false;
    private final Body body;
    private final SootMethod method;
    private final PhantomRetStmt phantomRetStmt;
    // the context of this method
    private final Context context;
    // the summary for all methods
    // method, context, statement, variable -> set of abstractLoc
    private final Map<SootMethod, Map<Context, Map<UniqueStmt, Map<Value,Set<AbstractLoc>>>>> methodSummary;
    // the summary for this method with the current context
    // statement, variable -> set of abstractLoc
    private final Map<UniqueStmt, Map<Value,Set<AbstractLoc>>> currMethodSummary;
    // the final summary of this object, return value and arguments at the return of this method
    private final List<Set<AbstractLoc>> finalMethodSummary;

    // Three data structures below are used for the initialization of uniqueStmt
    // stores the overall count number of string of each statement
    private final Map<String, Integer> stmtStrCounter;
    // stores the count id of string of each statement
    private final Map<Stmt, Integer> countedStmtCache;
    // stores the generated UniqueStmt(in order to reduce repetitious object generation)
    private final Map<UniqueStmt, UniqueStmt> uniqueStmtCache;

    /* The complete constructor
    *  we need to pass the callStringLen as a config
    *  we suppose that callString has been checked and its length is no greater than callStringLen
    */
    public PointsToAnalysis(Body body, Context context,
                            Map<SootMethod, Map<Context, Map<UniqueStmt, Map<Value,Set<AbstractLoc>>>>> methodSummary,
                            Map<String, Integer> stmtStrCounter, Map<Stmt, Integer> countedStmtCache,
                            Map<UniqueStmt, UniqueStmt> uniqueStmtCache) {
        super(new ExceptionalUnitGraph(body));
        this.body = body;
        this.method = body.getMethod();
        this.phantomRetStmt = PhantomRetStmt.getInstance(method);

        this.context = context;
        this.methodSummary = methodSummary;
        // copy the summary of context to finalMethodSummary
        this.finalMethodSummary = new ArrayList<>();
        this.finalMethodSummary.addAll(context.getSummary());

        this.stmtStrCounter = stmtStrCounter;
        this.countedStmtCache = countedStmtCache;
        this.uniqueStmtCache = uniqueStmtCache;

        // Sanity check
        assertNotNull(body);
        assertNotNull(context);
        assertNotNull(methodSummary);

        // Initialize methodSummary for current method with context (if not done yet)
        this.methodSummary.putIfAbsent(method, new HashMap<>());
        if (!this.methodSummary.get(method).containsKey(context)) {
            this.methodSummary.get(method).put(context, new HashMap<>());
        }
        currMethodSummary = this.methodSummary.get(method).get(context);
    }

    /**
     * Do forward dataflow analysis
     */
    public void doAnalysis() {
       logger.debug("Analyzing method {} for pointer analysis", method);
       super.doAnalysis();
    }

    /**
     * Visit each node in CFG, use flow function to calculate out-set according to in-set
     * @param in        in-set of map from variable to pts
     * @param unit      the current node to visit in CFG
     * @param out       out-set of map from variable to pts
     */
    @Override
    protected void flowThrough(Map<Value, Set<AbstractLoc>> in, Unit unit, Map<Value, Set<AbstractLoc>> out) {
        // clear out-set first
        out.clear();

        Stmt stmt = (Stmt) unit;
        assertNotNull(stmt);

        // get the uniqueStmt of the current statement
        UniqueStmt uniqueStmt = generateUniqueStmt(stmt);
        assertNotNull(uniqueStmt);
        // marks that this UniqueStmt has been visited
        currMethodSummary.putIfAbsent(uniqueStmt, new HashMap<>());

        // check whether the stmt is a new(init) statement or a normal assignment statement
        if (stmt instanceof AssignStmt) {
            AssignStmt assignStmt = (AssignStmt) stmt;
            Value rightOp = assignStmt.getRightOp();
            assertNotNull(rightOp);
            // new(init) statement
            if (rightOp instanceof JNewExpr) {
                visitNew(in, uniqueStmt, out);
            }
            // normal assignment statement
            else {
                visitNormalAssign(in, uniqueStmt, out);
            }
        }
        // the statement is an invoke statement,
        // pass the points-to set of this object, return value and parameters
        else if (stmt instanceof InvokeStmt) {
            InvokeExpr invoke = stmt.getInvokeExpr();
            assertNotNull(invoke);
            visitInvoke(in, uniqueStmt, invoke, out);
        }
        // the statement is a return statement,
        // record the points-to set of this object, return value and parameters into summary
        else if (stmt instanceof ReturnStmt || stmt instanceof ReturnVoidStmt) {
            visitReturn(in, uniqueStmt, out);
        }
        // the statement is not an assignment statement, just treat it as normal
        else {
            visitNormal(in, uniqueStmt, out);
        }
    }

    /**
     * Visit a new(init) statement
     * we will allocate a location for a variable
     * @param in            in-set of map
     * @param uniqueStmt    the current new(init) statement
     * @param out           out-set of map
     */
    private void visitNew(Map<Value, Set<AbstractLoc>> in, UniqueStmt uniqueStmt,
                          Map<Value, Set<AbstractLoc>> out) {
        // (strong) update in-set to currMethodSummary
        currMethodSummary.get(uniqueStmt).putAll(in);

        // get the current new(init) statement
        Stmt stmt = uniqueStmt.getStmt();
        AssignStmt assignStmt = (AssignStmt) stmt;

        // get the variable that refers to the new object
        Value leftOp = assignStmt.getLeftOp();

        // get the type of new object
        Value rightOp = assignStmt.getRightOp();
        Type objectType = rightOp.getType();

        // allocate a new heap location
        AbstractLoc newLoc = new AbstractLoc(method, context, uniqueStmt, objectType);
        Set<AbstractLoc> abstractLocs = new HashSet<>();
        abstractLocs.add(newLoc);

        // strong update: use {newLoc} to replace the original points-to set of leftOp,
        currMethodSummary.get(uniqueStmt).put(leftOp, abstractLocs);

        // get out-set from currMethodSummary
        out.putAll(currMethodSummary.get(uniqueStmt));
    }

    /**
     * Visit a normal assignment statement
     * we will update the location for a variable
     * @param in            in-set of map
     * @param uniqueStmt    the current normal assignment statement
     * @param out           out-set of map
     */
    private void visitNormalAssign(Map<Value, Set<AbstractLoc>> in, UniqueStmt uniqueStmt,
                                   Map<Value, Set<AbstractLoc>> out) {
        // (strong) update in-set to currMethodSummary
        currMethodSummary.get(uniqueStmt).putAll(in);

        // get the current assign statement
        Stmt stmt = uniqueStmt.getStmt();
        AssignStmt assignStmt = (AssignStmt) stmt;

        // get the variable of left and right operators
        Value leftOp = assignStmt.getLeftOp();
        Value rightOp = assignStmt.getRightOp();

        // strong update: use pts(rightOp) to replace pts(leftOp)
        Set<AbstractLoc> rightOpPts = new HashSet<>();
        if (currMethodSummary.get(uniqueStmt).containsKey(rightOp)) {
            rightOpPts =currMethodSummary.get(uniqueStmt).get(rightOp);
        }
        currMethodSummary.get(uniqueStmt).put(leftOp, rightOpPts);

        // get out-set from currMethodSummary
        out.putAll(currMethodSummary.get(uniqueStmt));
    }

    /**
     * Visit an invoke statement,
     * pass the points-to set of this object and arguments from this summary to callee summary,
     * also pass the points-to set of this object, return value and arguments from callee summary to this summary
     * @param in            in-set of map
     * @param uniqueStmt    the current invoke statement
     * @param invoke        the current invoke expression
     * @param out           out-set of map
     */
    private void visitInvoke(Map<Value, Set<AbstractLoc>> in, UniqueStmt uniqueStmt,
                             InvokeExpr invoke, Map<Value, Set<AbstractLoc>> out) {
        // get the callee method
        Stmt stmt = uniqueStmt.getStmt();
        SootMethod calleeMethod = invoke.getMethod();
        assertNotNull(calleeMethod);

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

        // get the new context for callee method
        int argNum = calleeMethod.getParameterCount();
        Context newContext = generateNewContext(uniqueStmt, argNum);

        // pass the points-to set of this object and arguments from this summary to callee summary
        // 1. index 0 means summary[0] = points-to set of base(this) object
        newContext.setSummary(0, currMethodSummary.get(uniqueStmt).get(base));
        // 2. index i+2 means summary[i+2] = points-to set of the ith argument
        for (int i = 0; i < argNum; ++i) {
            Value arg = invoke.getArg(i);
            newContext.setSummary(i+2,currMethodSummary.get(uniqueStmt).get(arg));
        }

        // pass the points-to set of this object, return value and arguments
        // from callee summary to this summary
        // 1. pass the points-to set of this object


    }

    /**
     * Visit a return statement
     * record the points-to set of this object, return value and arguments into finalMethodSummary in callee
     * @param in
     * @param uniqueStmt
     * @param out
     */
    private void visitReturn(Map<Value, Set<AbstractLoc>> in, UniqueStmt uniqueStmt,
                             Map<Value, Set<AbstractLoc>> out) {

    }

    /**
     * Visit a normal statement(not an assignment statement)
     * @param in            in-set of map
     * @param uniqueStmt    the current normal statement
     * @param out           out-set of map
     */
    private void visitNormal(Map<Value, Set<AbstractLoc>> in, UniqueStmt uniqueStmt,
                                   Map<Value, Set<AbstractLoc>> out) {
        // (strong) update in-set to currMethodSummary
        currMethodSummary.get(uniqueStmt).putAll(in);

        // let out = in since there is no change
        out.putAll(in);
    }

    /**
     * Initialize the in-set of maps for each node except the entry node
     * @return      the initial in-set of maps
     */
    @Override
    protected Map<Value, Set<AbstractLoc>> newInitialFlow() {
        return new HashMap<>();
    }

    /**
     * Initialize the in-set of maps at the entry node,
     * @return      the initial in-set of maps
     */
    @Override
    protected Map<Value, Set<AbstractLoc>> entryInitialFlow() {
        // init it
        return new HashMap<>();
    }

    /**
     * Merge method as a meet operator, which is Union for maps
     * @param in1       one in-set of maps for a node
     * @param in2       another in-set of maps for a node
     * @param out       out-set of maps for a node
     */
    @Override
    protected void merge(Map<Value, Set<AbstractLoc>> in1, Map<Value, Set<AbstractLoc>> in2,
                         Map<Value, Set<AbstractLoc>> out) {
        out.clear();
        out.putAll(in1);
        // if the key set of in1 and in2 intersects,
        // we should do union operation to the corresponding set<AbstractLoc>.
        in2.forEach((key, value) -> out.merge(key, value, (oldValue, newValue) -> {
            newValue.addAll(oldValue);
            return newValue;
        }));
    }

    /**
     * Copy from source to destination
     * @param source        source map
     * @param dest          destination map
     */
    @Override
    protected void copy(Map<Value, Set<AbstractLoc>> source, Map<Value, Set<AbstractLoc>> dest) {
        dest.clear();
        dest.putAll(source);
    }

    /**
     * Generate a new context for callee method
     * The summary of that context has not been set yet
     * @param uniqueStmt    the uniqueStmt
     * @param argNum        the number of argu
     * @return              a new context for callee method
     */
    private Context generateNewContext(UniqueStmt uniqueStmt, int argNum) {
        // get the basic info of callString
        int callStringLen = context.getCallStringLen();
        Queue<UniqueStmt> callString = context.getCallString();

        // add the current call site into the callString
        callString.add(uniqueStmt);

        // construct a new context, whose summary is empty(of course, capacity is set)
        Context newContext = new Context(callStringLen, callString, argNum);
        return newContext;
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
