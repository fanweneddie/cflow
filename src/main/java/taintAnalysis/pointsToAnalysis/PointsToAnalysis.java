package taintAnalysis.pointsToAnalysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;

import taintAnalysis.UniqueStmt;

import java.util.*;
import java.util.List;

import static assertion.Assert.assertNotNull;

/**
 * The framework of a context-, flow-, base-sensitive inter-procedural points-to analysis
 * (by calling intra-procedural analysis multiple times to simulate inter-procedural analysis)
 * lattice: a map of <variable/object, set<AbstractLoc>>
 * direction: forward
 * meet operator: map union
 * transfer function: strong update
 */
public class PointsToAnalysis extends ForwardFlowAnalysis<Unit, Map<Value, PointsToSet>> {
    // here I copy the fields in TaintFlowAnalysis since their framework is similar
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private static final CallGraph cg = Scene.v().hasCallGraph() ? Scene.v().getCallGraph() : null;

    private final Body body;
    private final SootMethod method;
    // the context of this method
    private final Context context;
    // the summary for all methods
    // method, context, statement, variable -> points-to set
    private final Map<SootMethod, Map<Context, Map<UniqueStmt, Map<Value, PointsToSet>>>> methodSummary;
    // the summary for this method with the current context
    // statement, variable -> points-to set
    private final Map<UniqueStmt, Map<Value, PointsToSet>> currMethodSummary;
    // the list of variables that refers to this object, return value and arguments
    // index: 0 for this object, 1 for return value, i + 2 for argument i
    private final List<Value> specialVarList;
    // the final statement in this method(mostly return statement)
    private UniqueStmt finalStmt;
    private final LibMethodWrapper libMethodWrapper;

    // Three data structures below are used for the initialization of uniqueStmt
    // stores the overall count number of string of each statement
    private final Map<String, Integer> stmtStrCounter;
    // stores the count id of string of each statement
    private final Map<Stmt, Integer> countedStmtCache;
    // stores the generated UniqueStmt(in order to reduce repetitious object generation)
    private final Map<UniqueStmt, UniqueStmt> uniqueStmtCache;
    // stores visited method, in order to eliminate recursive invoke
    private final Set<SootMethod> visitedMethods;
    private final Set<SootMethod> visitedLocalMethods;

    public PointsToAnalysis(Body body, Context context,
                            Map<SootMethod, Map<Context, Map<UniqueStmt, Map<Value,PointsToSet>>>> methodSummary,
                            LibMethodWrapper libMethodWrapper,
                            Map<String, Integer> stmtStrCounter, Map<Stmt, Integer> countedStmtCache,
                            Map<UniqueStmt, UniqueStmt> uniqueStmtCache, Set<SootMethod> visitedMethods) {
        super(new ExceptionalUnitGraph(body));
        this.body = body;
        this.method = body.getMethod();

        this.context = context;
        this.methodSummary = methodSummary;
        int summarySize = this.context.getSummary().size();
        this.specialVarList = new ArrayList<>(summarySize);
        this.libMethodWrapper = libMethodWrapper;

        this.stmtStrCounter = stmtStrCounter;
        this.countedStmtCache = countedStmtCache;
        this.uniqueStmtCache = uniqueStmtCache;
        this.visitedMethods = visitedMethods;
        this.visitedLocalMethods = new HashSet<>();

        // Sanity check
        assertNotNull(body);
        assertNotNull(context);
        assertNotNull(methodSummary);
        assertNotNull(libMethodWrapper);
        assertNotNull(visitedMethods);

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

        // for debugging
        logger.info(method.toString());
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
    protected void flowThrough(Map<Value, PointsToSet> in, Unit unit, Map<Value, PointsToSet> out) {

        Stmt stmt = (Stmt) unit;
        assertNotNull(stmt);
        // get the uniqueStmt of the current statement
        UniqueStmt uniqueStmt = generateUniqueStmt(stmt);
        assertNotNull(uniqueStmt);

        // marks that this UniqueStmt has been visited
        currMethodSummary.putIfAbsent(uniqueStmt, new HashMap<>());

        // (strong) update in-set to currMethodSummary
        currMethodSummary.get(uniqueStmt).clear();
        currMethodSummary.get(uniqueStmt).putAll(in);

        if (method.toString().contains("readTagFromConfig")) {
            logger.info(stmt.toString());
        }

        // check whether the statement is an identity statement at the start of this method
        if (stmt instanceof JIdentityStmt) {
            visitIdentity(uniqueStmt);
        }
        // check whether the stmt is a new statement or a normal assignment statement
        else if (stmt instanceof AssignStmt) {
            AssignStmt assignStmt = (AssignStmt) stmt;
            Value rightOp = assignStmt.getRightOp();
            assertNotNull(rightOp);
            // new statement
            if (rightOp instanceof JNewExpr || rightOp instanceof JNewArrayExpr) {
                visitNew(uniqueStmt);
            }
            // normal assignment statement
            else {
                visitNormalAssign(uniqueStmt);
            }
        }
        // the statement is an invoke statement,
        // if it is an init invoke statement of Object, then we allocate an object
        // else, we pass the points-to set of this object, return value and parameters
        else if (stmt instanceof InvokeStmt) {
            InvokeExpr invoke = stmt.getInvokeExpr();
            assertNotNull(invoke);
            assert(invoke instanceof InstanceInvokeExpr);
            visitInvoke(uniqueStmt, invoke);
        }
        // the statement is a return statement,
        // record the points-to set of this object, return value and parameters into summary
        else if (stmt instanceof JThrowStmt || stmt instanceof ReturnStmt || stmt instanceof ReturnVoidStmt) {
            visitReturn(uniqueStmt);
        }

        // at last, get out-set from currMethodSummary
        out.clear();
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
            PointsToSet thisPts = context.getSummary().get(0);
            currMethodSummary.get(uniqueStmt).put(leftVal, thisPts);
            specialVarList.set(0, leftVal);
        }
        // 2. rightVal represents arguments
        else if (rightVal instanceof ParameterRef) {
            ParameterRef arg = (ParameterRef) rightVal;
            // get the index number of this argument(the index of the first argument is 0).
            int index = arg.getIndex();
            PointsToSet argPts = context.getSummary().get(index + 2);
            // here, the type of left value is not reference
            currMethodSummary.get(uniqueStmt).put(leftVal, argPts);
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

        // set its points-to set as empty
        currMethodSummary.get(uniqueStmt).put(leftOp, PointsToSet.getNullPts());
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
        // and the value to be assigned is not a primitive type
        else if (rightOp.getType() instanceof RefLikeType) {

            // there are five types of assignments:
            // 1. a = b
            // 2. a.f = b
            // 3. a = b.f
            // 4. a[i] = b
            // 5. a = b[i]
            // we assume that the pointsToSet of right operator is stored in currMethodSummary

            // get the pointsToSet of right operand
            PointsToSet rightOpPts;
            // 1. right operand is a reference to a field,
            // we should get the pointsToSet of that field
            if (rightOp instanceof JInstanceFieldRef) {
                Value rightOpBase = ((JInstanceFieldRef) rightOp).getBase();
                SootField rightOpField = ((JInstanceFieldRef) rightOp).getFieldRef().resolve();
                PointsToSet rightOpBasePts = currMethodSummary.get(uniqueStmt).get(rightOpBase);
                assert(rightOpBasePts != null);
                rightOpPts = rightOpBasePts.getFieldPts(rightOpField);
                if (rightOpPts == null) {
                    rightOpPts = new PointsToSet();
                }
            }
            // 2. right operand is
            else if (rightOp instanceof JArrayRef) {
                Value rightOpArrRef = ((JArrayRef) rightOp).getBaseBox().getValue();
                PointsToSet rightOpArrRefPts = currMethodSummary.get(uniqueStmt).get(rightOpArrRef);
                assert (rightOpArrRefPts != null);
                rightOpPts = rightOpArrRefPts.getElePtsList().get(0);
            }
            // 3. right operand is just an object
            // just get its pointsToSet
            else {
                if (currMethodSummary.get(uniqueStmt).containsKey(rightOp)) {
                    rightOpPts = currMethodSummary.get(uniqueStmt).get(rightOp);
                }
                ////  useful ???
                else {
                    rightOpPts = new PointsToSet();
                }
            }

            // update the pointsToSet of left operand
            PointsToSet leftOpPts;
            // 1. left operand is a reference to a field,
            // we should update the pointsToSet of that field
            if (leftOp instanceof JInstanceFieldRef) {
                Value leftOpBase = ((JInstanceFieldRef) leftOp).getBase();
                SootField leftOpField = ((JInstanceFieldRef) leftOp).getFieldRef().resolve();
                // get the pointsToSet of base object of left operand
                PointsToSet leftOpBasePts;
                if (currMethodSummary.get(uniqueStmt).containsKey(leftOpBase)) {
                    leftOpBasePts = currMethodSummary.get(uniqueStmt).get(leftOpBase);
                } else {
                    leftOpBasePts = new PointsToSet();
                }
                // (strong) update the pointsToSet of the field
                leftOpBasePts.addField(leftOpField, rightOpPts);
            }
            // 2. left operand is a reference to an array
            //
            else if (leftOp instanceof JArrayRef) {
                Value leftOpArrRef = ((JArrayRef) leftOp).getBaseBox().getValue();
                // (strong) update the pointsToSet of the element
                List<PointsToSet> leftOpArrEle = currMethodSummary.get(uniqueStmt).get(leftOpArrRef).getElePtsList();
                if(leftOpArrEle.size() == 0) {
                    leftOpArrEle.add(rightOpPts);
                } else {
                    leftOpArrEle.set(0, rightOpPts);
                }
            }
            // 3. left operand is just an object
            // we should update its pointsToSet
            else {
                leftOpPts = new PointsToSet(rightOpPts);
                // strong update: use pts(rightOp) to replace pts(leftOp)
                currMethodSummary.get(uniqueStmt).put(leftOp, leftOpPts);
            }
        }
    }

    /**
     * Visit an init invoke statement of Object
     * And we will allocate a location for a variable.
     * If allocObject is Base, then stmt is invokeStmt
     * If allocObject is RetVal, then stmt is assignStmt
     * @param uniqueStmt    the current new(init) statement
     * @param newObject     the new object to be allocated,
     *                      it can be a base object or a return value
     */
    private void visitAlloc(UniqueStmt uniqueStmt, Value newObject) {

        // allocate a new heap location and get the pointsToSet for newObject
        Type objectType = newObject.getType();
        PointsToSet pts = new PointsToSet(method, context, uniqueStmt, objectType);

        // strong update: use {newLoc} to replace the original points-to set of newObject,
        currMethodSummary.get(uniqueStmt).put(newObject, pts);
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

        // to avoid invoking the same method in a loop
        if (visitedLocalMethods.contains(calleeMethod)) {
            return;
        }

        // Get the base object of this invocation in caller (if applies)
        Value base = null;
        if (invoke instanceof InstanceInvokeExpr) {
            base = ((InstanceInvokeExpr) invoke).getBase();
        }

        // get the return value of this invocation for future use
        Value retVal = null;
        if (stmt instanceof AssignStmt) {
            retVal = ((AssignStmt) stmt).getLeftOp();
        }

        // check the type of this method,
        // then we will leverage libWrapper to see whether we will analyze this method

        // 1. analyze callee method directly(if possible)
        // we use visitedMethod to check whether the invoke is recursive
        // we don't analyze recursive call
        if (calleeMethod.isConcrete() && !libMethodWrapper.check(calleeMethod)
                && !visitedMethods.contains(calleeMethod)) {

            // update methodSummary for calleeMethod
            methodSummary.putIfAbsent(calleeMethod, new HashMap<>());
            Map<Context, Map<UniqueStmt, Map<Value, PointsToSet>>> calleeSummary = methodSummary.get(calleeMethod);

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
                newContext.setSummary(0, new PointsToSet());
            }
            // 2. index 1 means summary[1] = empty set for initialization
            newContext.setSummary(1, new PointsToSet());
            // 3. index i+2 means summary[i+2] = points-to set of the ith argument
            for (int i = 0; i < argNum; ++i) {
                Value arg = invoke.getArg(i);
                // get the pts of arg if it is not a primitive type
                PointsToSet argPts = new PointsToSet();
                if (arg.getType() instanceof RefLikeType) {
                    if (currMethodSummary.get(uniqueStmt).containsKey(arg)) {
                        argPts = currMethodSummary.get(uniqueStmt).get(arg);
                    }
                }
                newContext.setSummary(i + 2, argPts);
            }

            // record newContext into calleeSummary
            calleeSummary.putIfAbsent(newContext, new HashMap<>());

            // update methodSummary and finalMethodSummary for calleeMethod
            methodSummary.putIfAbsent(calleeMethod, new HashMap<>());
            methodSummary.get(calleeMethod).putIfAbsent(newContext, new HashMap<>());

            Body calleeBody = calleeMethod.retrieveActiveBody();
            assertNotNull(calleeBody);
            visitedMethods.add(calleeMethod);
            visitedLocalMethods.add(calleeMethod);
            PointsToAnalysis calleeAnalysis = new PointsToAnalysis(calleeBody, newContext, methodSummary,
                    libMethodWrapper, stmtStrCounter,
                    countedStmtCache, uniqueStmtCache, visitedMethods);
            calleeAnalysis.doAnalysis();
            visitedMethods.remove(calleeMethod);

            // pass the points-to set of this object, return value and arguments
            // from final summary of callee to this summary
            Map<Value, PointsToSet> calleeFinalSummary = methodSummary.
                    get(calleeMethod).get(newContext).get(calleeAnalysis.getFinalStmt());

            // 1. pass the points-to set of this object
            Value calleeBase = calleeAnalysis.getSpecialVarList().get(0);
            if (calleeBase != null && calleeBase.getType() instanceof RefLikeType) {
                PointsToSet basePts;
                if (calleeBase instanceof NullConstant) {
                    basePts = PointsToSet.getNullPts();
                } else {
                    basePts = calleeFinalSummary.get(calleeBase);
                }
                currMethodSummary.get(uniqueStmt).put(base, basePts);
            }

            // 2. pass the points-to set of return value(if exists)
            // Note that retVal would be null if it is a primitive type
            Value calleeRetVal = calleeAnalysis.getSpecialVarList().get(1);
            if (calleeRetVal != null && calleeRetVal.getType() instanceof RefLikeType) {
                PointsToSet retValPts;
                if (calleeRetVal instanceof NullConstant) {
                    retValPts = PointsToSet.getNullPts();
                } else {
                    retValPts = calleeFinalSummary.get(calleeRetVal);
                }
                currMethodSummary.get(uniqueStmt).put(retVal, retValPts);
            }

            // 3. pass the points-to set of arguments
            for (int i = 0; i < argNum; ++i) {
                // argument in caller
                Value arg = invoke.getArg(i);
                // argument in callee
                Value calleeArg = calleeAnalysis.getSpecialVarList().get(i + 2);
                if (calleeArg != null && calleeArg.getType() instanceof RefLikeType) {
                    PointsToSet argPts;
                    if (calleeArg instanceof NullConstant) {
                        argPts = PointsToSet.getNullPts();
                    } else {
                        argPts = calleeFinalSummary.get(calleeArg);
                    }
                    currMethodSummary.get(uniqueStmt).put(arg, argPts);
                }
            }
        }
        // 2. for invoke to init method of a library class
        // we don't analyze the method, but we only allocate a space for base object
        else if (calleeMethod.getName().equals("<init>")
                && calleeMethod.getReturnType() instanceof VoidType) {
            visitedLocalMethods.add(calleeMethod);
            visitAlloc(uniqueStmt, base);
        }
        // 3. for invoke to a library method that returns a reference type
        // we don't analyze the method, but we should allocate the space for return value
        else if (retVal != null && calleeMethod.getReturnType() instanceof RefLikeType)
        {
            visitedLocalMethods.add(calleeMethod);
            visitAlloc(uniqueStmt, retVal);
        }
    }

    /**
     * Visit a return statement
     * Set this statement sa the finalStmt in this method
     * Record the return value in specialVarList
     * @param uniqueStmt    the current return statement
     */
    private void visitReturn(UniqueStmt uniqueStmt) {
        // record the final statement in this method
        // (note that there is only one return statement in each method body)
        finalStmt = uniqueStmt;
        Stmt stmt = uniqueStmt.getStmt();

        // record the return value in specialVarList
        // if it is not of primitive type
        if(stmt instanceof ReturnStmt) {
            ReturnStmt returnStmt = (ReturnStmt) stmt;
            Value retVal = returnStmt.getOpBox().getValue();
            if (retVal.getType() instanceof RefLikeType) {
                specialVarList.set(1, retVal);
            }
        }
    }

    /**
     * Initialize the in-set of points-to map for each node except the entry node
     * @return      the initial in-set of points-to map
     */
    @Override
    protected Map<Value, PointsToSet> newInitialFlow() { return new HashMap<>(); }

    /**
     * Initialize the in-set of points-to maps at the entry node,
     * @return      the initial in-set of points-to map
     */
    @Override
    protected Map<Value, PointsToSet> entryInitialFlow() { return new HashMap<>(); }

    /**W
     * Merge method as a meet operator, which is Union for maps
     * @param in1       one in-set of points-to map
     * @param in2       another in-set of points-to map
     * @param out       the out-set of points-to map
     */
    @Override
    protected void merge(Map<Value, PointsToSet> in1, Map<Value, PointsToSet> in2,
                         Map<Value, PointsToSet> out) {
        out.clear();
        out.putAll(in1);
        // merge the pointsToSet of each variable in out with in1
        for (Map.Entry<Value, PointsToSet> entry : in2.entrySet()) {
            Value variable = entry.getKey();
            PointsToSet pts = entry.getValue();
            if (out.containsKey(variable)) {
                out.get(variable).merge(pts);
            } else {
                PointsToSet newPts = new PointsToSet(pts);
                out.put(variable, newPts);
            }
        }
    }

    /**
     * Copy from source to destination
     * @param source        the source points-to map
     * @param dest          the destination points-to map
     */
    @Override
    protected void copy(Map<Value, PointsToSet> source, Map<Value, PointsToSet> dest) {
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
        // get the basic info of callString and create a new callString
        int callStringLen = context.getCallStringLen();
        Queue<UniqueStmt> newCallString = new LinkedList<>();
        newCallString.addAll(context.getCallString());

        // add the current call site into the NEW callString
        newCallString.add(uniqueStmt);

        // construct a new context, whose summary is empty
        Context newContext = new Context(callStringLen, newCallString, argNum);
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

    public UniqueStmt getFinalStmt() { return finalStmt; }

    public List<Value> getSpecialVarList() { return specialVarList; }
}
