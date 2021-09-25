package taintAnalysis.pointsToAnalysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.JIdentityStmt;
import soot.jimple.internal.JNewExpr;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;

import taintAnalysis.Taint;
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
    private final Map<UniqueStmt, Map<Value, Set<AbstractLoc>>> currMethodSummary;
    // the list of variables that refers to this object, return value and arguments
    // index: 0 for this object, 1 for return value, i + 2 for argument i
    private final List<Value> specialVarList;
    // the final summary of all methods with their context
    // method, context -> set of abstractLoc for this object, return value and arguments
    private final Map<SootMethod, Map<Context, List<Set<AbstractLoc>>>> finalMethodSummary;
    // the final summary of this object, return value and arguments at the return of this method
    // index: 0 for this object, 1 for return value, i + 2 for argument i
    private final List<Set<AbstractLoc>> currFinalMethodSummary;

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
                            Map<SootMethod, Map<Context, List<Set<AbstractLoc>>>> finalMethodSummary,
                            Map<String, Integer> stmtStrCounter, Map<Stmt, Integer> countedStmtCache,
                            Map<UniqueStmt, UniqueStmt> uniqueStmtCache) {
        super(new ExceptionalUnitGraph(body));
        this.body = body;
        this.method = body.getMethod();
        this.phantomRetStmt = PhantomRetStmt.getInstance(method);

        this.context = context;
        this.methodSummary = methodSummary;
        int summarySize = this.context.getSummarySize();
        this.specialVarList = new ArrayList<>(summarySize);
        this.finalMethodSummary = finalMethodSummary;

        this.stmtStrCounter = stmtStrCounter;
        this.countedStmtCache = countedStmtCache;
        this.uniqueStmtCache = uniqueStmtCache;

        // Sanity check
        assertNotNull(body);
        assertNotNull(context);
        assertNotNull(methodSummary);
        assertNotNull(finalMethodSummary);

        // Initialize methodSummary for current method with context (if not done yet)
        this.methodSummary.putIfAbsent(method, new HashMap<>());
        if (!this.methodSummary.get(method).containsKey(context)) {
            this.methodSummary.get(method).put(context, new HashMap<>());
        }
       this.currMethodSummary = this.methodSummary.get(method).get(context);

        // initialize specialVarList with null
        for (int i = 0; i < summarySize; ++i) {
            this.specialVarList.add(null);
        }

        // initialize currFinalMethodSummary
        this.finalMethodSummary.putIfAbsent(method, new HashMap<>());
        if (!this.finalMethodSummary.get(method).containsKey(context)) {
            this.finalMethodSummary.get(method).put(context, context.getSummary());
        }
        this.currFinalMethodSummary = this.finalMethodSummary.get(method).get(context);
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

        // (strong) update in-set to currMethodSummary
        currMethodSummary.get(uniqueStmt).putAll(in);

        // check whether the statement is an identity statement at the start of this method
        if (stmt instanceof JIdentityStmt) {
            visitIdentity(uniqueStmt);
        }
        // check whether the stmt is a new(init) statement or a normal assignment statement
        else if (stmt instanceof AssignStmt) {
            AssignStmt assignStmt = (AssignStmt) stmt;
            Value rightOp = assignStmt.getRightOp();
            assertNotNull(rightOp);
            // new(init) statement
            if (rightOp instanceof JNewExpr) {
                visitNew(uniqueStmt);
            }
            // normal assignment statement
            else {
                visitNormalAssign(uniqueStmt);
            }
        }
        // the statement is an invoke statement,
        // pass the points-to set of this object, return value and parameters
        else if (stmt instanceof InvokeStmt) {
            InvokeExpr invoke = stmt.getInvokeExpr();
            assertNotNull(invoke);
            visitInvoke(uniqueStmt, invoke);
        }
        // the statement is a return statement,
        // record the points-to set of this object, return value and parameters into summary
        else if (stmt instanceof ReturnStmt || stmt instanceof ReturnVoidStmt) {
            visitReturn(uniqueStmt);
        }

        // at last, get out-set from currMethodSummary
        out.putAll(currMethodSummary.get(uniqueStmt));
    }

    /**
     * Visit an identity statement
     * we will copy the context into the summary of this object and arguments
     * @param uniqueStmt        the current identity statement
     */
    private void visitIdentity(UniqueStmt uniqueStmt) {
        // get the left value and right value
        // right value is this object or argument
        // left value is the variable that refers to the right value
        Stmt stmt = uniqueStmt.getStmt();
        JIdentityStmt idStmt = (JIdentityStmt) stmt;
        Value leftVal = idStmt.getLeftOpBox().getValue();
        Value rightVal = idStmt.getRightOpBox().getValue();

        // check whether rightVal represents this object or argument
        // and we will copy its corresponding summary in context to currMethodSummary
        // also we will set record the left value in specialVarList to show
        // which variable refers to this object/arguments.

        // 1. rightVal represents this object
        if (rightVal instanceof ThisRef) {
            currMethodSummary.get(uniqueStmt).put(leftVal, context.getSummary().get(0));
            specialVarList.set(0, leftVal);
        }
        // 2. rightVal represents arguments
        else if (rightVal instanceof ParameterRef) {
            ParameterRef arg = (ParameterRef) rightVal;
            // get the index number of this argument. The index of the first argument is 0.
            int index = arg.getIndex();
            currMethodSummary.get(uniqueStmt).put(leftVal, context.getSummary().get(index + 2));
            specialVarList.set(index + 2, leftVal);
        }
    }

    /**
     * Visit a new(init) statement
     * we will allocate a location for a variable
     * @param uniqueStmt    the current new(init) statement
     */
    private void visitNew(UniqueStmt uniqueStmt) {

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
    }

    /**
     * Visit a normal assignment statement
     * we will update the location for a variable
     * @param uniqueStmt    the current normal assignment statement
     */
    private void visitNormalAssign(UniqueStmt uniqueStmt) {

        // get the current assign statement
        Stmt stmt = uniqueStmt.getStmt();
        AssignStmt assignStmt = (AssignStmt) stmt;

        // get the variable of left and right operators
        Value leftOp = assignStmt.getLeftOp();
        Value rightOp = assignStmt.getRightOp();

        // check whether it is a normal assign statement or an invoke assign statement
        // 1. the statement is an invoke assign statement(e.g. r = a.m(b1,b2))
        if (stmt.containsInvokeExpr()) {
            InvokeExpr invoke = stmt.getInvokeExpr();
            visitInvoke(uniqueStmt, invoke);
        }
        // 2. the statement is a normal assign statement(e.g. r = a)
        else {
            // record the abstractLoc of rightOp if it is a constant
            if (rightOp instanceof Constant) {
                AbstractLoc abstractLoc = new AbstractLoc(rightOp.getType(), rightOp);
                if (currMethodSummary.get(uniqueStmt).containsKey(rightOp)) {
                    currMethodSummary.get(uniqueStmt).get(rightOp).add(abstractLoc);
                } else {
                    Set<AbstractLoc> rightOpPts = new HashSet<>();
                    rightOpPts.add(abstractLoc);
                    currMethodSummary.get(uniqueStmt).put(rightOp, rightOpPts);
                }
            }

            // the points-to set of right operator
            Set<AbstractLoc> rightOpPts = new HashSet<>();
            // strong update: use pts(rightOp) to replace pts(leftOp)
            if (currMethodSummary.get(uniqueStmt).containsKey(rightOp)) {
                rightOpPts = currMethodSummary.get(uniqueStmt).get(rightOp);
            }
            currMethodSummary.get(uniqueStmt).put(leftOp, rightOpPts);
        }
    }

    /**
     * Visit an invoke statement,
     * pass the points-to set of this object and arguments from this summary to callee summary,
     * also pass the points-to set of this object, return value and arguments from callee summary to this summary
     * @param uniqueStmt    the current invoke statement
     * @param invoke        the current invoke expression
     */
    private void visitInvoke(UniqueStmt uniqueStmt, InvokeExpr invoke) {
        // get the callee method and its summary
        Stmt stmt = uniqueStmt.getStmt();
        SootMethod calleeMethod = invoke.getMethod();
        assertNotNull(calleeMethod);

        // update methodSummary for calleeMethod
        methodSummary.putIfAbsent(calleeMethod, new HashMap<>());
        Map<Context, Map<UniqueStmt, Map<Value,Set<AbstractLoc>>>> calleeSummary = methodSummary.get(calleeMethod);

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
        // for non-static invoke
        if (base != null) {
            newContext.setSummary(0, currMethodSummary.get(uniqueStmt).get(base));
        }
        // for static invoke, not sure that it is right
        else {
            newContext.setSummary(0, new HashSet<>());
        }
        // 2. index 1 means summary[1] = empty set for initialization
        newContext.setSummary(1, new HashSet<>());
        // 3. index i+2 means summary[i+2] = points-to set of the ith argument
        for (int i = 0; i < argNum; ++i) {
            Value arg = invoke.getArg(i);
            // record the abstractLoc of arg if it is a constant
            if (arg instanceof Constant) {
                AbstractLoc abstractLoc = new AbstractLoc(arg.getType(), arg);
                if (currMethodSummary.get(uniqueStmt).containsKey(arg)) {
                    currMethodSummary.get(uniqueStmt).get(arg).add(abstractLoc);
                } else {
                    Set<AbstractLoc> argPts = new HashSet<>();
                    argPts.add(abstractLoc);
                    currMethodSummary.get(uniqueStmt).put(arg, argPts);
                }
            }
            newContext.setSummary(i + 2, currMethodSummary.get(uniqueStmt).get(arg));
        }

        // record newContext into calleeSummary
        calleeSummary.putIfAbsent(newContext, new HashMap<>());

        // update finalMethodSummary for calleeMethod
        finalMethodSummary.putIfAbsent(calleeMethod, new HashMap<>());
        finalMethodSummary.get(calleeMethod).putIfAbsent(newContext, newContext.getSummary());

        // pass the points-to set of this object, return value and arguments
        // from callee summary to this summary
        List<Set<AbstractLoc>> calleeFinalMethodSummary = finalMethodSummary.get(calleeMethod).get(newContext);
        // 1. pass the points-to set of this object
        Set<AbstractLoc> basePts = calleeFinalMethodSummary.get(0);
        currMethodSummary.get(uniqueStmt).put(base, basePts);
        // 2. pass the points-to set of return value
        Set<AbstractLoc> retValPts = calleeFinalMethodSummary.get(1);
        currMethodSummary.get(uniqueStmt).put(retVal, retValPts);
        // 3. pass the points-to set of arguments
        for (int i = 0; i < argNum; ++i) {
            Value arg = invoke.getArg(i);
            Set<AbstractLoc> argPts = calleeFinalMethodSummary.get(i + 2);
            currMethodSummary.get(uniqueStmt).put(arg, argPts);
        }
    }

    /**
     * Visit a return statement
     * record the points-to set of this object, return value and arguments into finalMethodSummary
     * if the recording changes finalMethodSummary, that means that we haven't reached a fixed point.
     * @param uniqueStmt    the current return statement
     */
    private void visitReturn(UniqueStmt uniqueStmt) {
        Stmt stmt = uniqueStmt.getStmt();

        // the summary in context
        List<Set<AbstractLoc>> contextSummary = context.getSummary();

        // 1. record the points-to set of this object and check whether change occurs
        Value thiz = specialVarList.get(0);
        // this object is used in the method
        if (thiz != null) {
            Set<AbstractLoc> thisPts = currMethodSummary.get(uniqueStmt).get(thiz);
            currFinalMethodSummary.set(0, thisPts);
            changed |= contextSummary.get(0).equals(thisPts);
        }
        // this object is not used in the method, so pass its summary on
        else {
            currFinalMethodSummary.set(0, contextSummary.get(0));
        }

        // 2. record the points-to set of return value and check whether change occurs
        if(stmt instanceof ReturnStmt) {
            ReturnStmt returnStmt = (ReturnStmt) stmt;
            Value retVal = returnStmt.getOpBox().getValue();
            specialVarList.set(1, retVal);
            // record the abstractLoc of return value if it is a constant
            if (retVal instanceof Constant) {
                AbstractLoc abstractLoc = new AbstractLoc(retVal.getType(), retVal);
                if (currMethodSummary.get(uniqueStmt).containsKey(retVal)) {
                    currMethodSummary.get(uniqueStmt).get(retVal).add(abstractLoc);
                } else {
                    Set<AbstractLoc> retValPts = new HashSet<>();
                    retValPts.add(abstractLoc);
                    currMethodSummary.get(uniqueStmt).put(retVal, retValPts);
                }
            }

            Set<AbstractLoc> retValPts = currMethodSummary.get(uniqueStmt).get(retVal);
            currFinalMethodSummary.set(1, retValPts);
            changed |= contextSummary.get(1).equals(retValPts);
        }

        // 3. record the points-to set of arguments and check whether change occurs
        int argNum = context.getSummarySize() - 2;
        for(int i = 0; i < argNum; ++i) {
            Value arg = specialVarList.get(i + 2);
            // arg is used in the method
            if (arg != null) {
                Set<AbstractLoc> argPts = currMethodSummary.get(uniqueStmt).get(arg);
                currFinalMethodSummary.set(i + 2, argPts);
                changed |= contextSummary.get(i + 2).equals(argPts);
            }
            // arg is not used in the method
            else {
                currFinalMethodSummary.set(i + 2, contextSummary.get(i + 2));
            }
        }
    }

    /**
     * Initialize the in-set of maps for each node except the entry node
     * @return      the initial in-set of maps
     */
    @Override
    protected Map<Value, Set<AbstractLoc>> newInitialFlow() { return new HashMap<>(); }

    /**
     * Initialize the in-set of maps at the entry node,
     * @return      the initial in-set of maps
     */
    @Override
    protected Map<Value, Set<AbstractLoc>> entryInitialFlow() { return new HashMap<>(); }

    /**W
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

    public boolean isChanged() {
        return changed;
    }

}
