package taintAnalysis.pointsToAnalysis;

import soot.jimple.toolkits.callgraph.Edge;
import taintAnalysis.pointsToAnalysis.pointsToSet.PointsToSet;
import taintAnalysis.pointsToAnalysis.pointsToSet.ObjRefPointsToSet;
import taintAnalysis.pointsToAnalysis.pointsToSet.ArrRefPointsToSet;

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

import static assertion.Assert.assertNotNull;

/**
 * The framework of a context-, flow-, base-sensitive inter-procedural points-to analysis
 * (by calling intra-procedural analysis multiple times to simulate inter-procedural analysis)
 * lattice: a map of <variable/object, set<AbstractLoc>>
 * direction: forward
 * meet operator: map intersection
 * transfer function: strong update
 */
public class PointsToAnalysis extends ForwardFlowAnalysis<Unit, Map<Value, PointsToSet>> {
    // The logger to record log information
    private final Logger logger = LoggerFactory.getLogger(getClass());
    // The call graph to get a more precise call method
    // It is not null if spark option is enabled
    private static final CallGraph cg = Scene.v().hasCallGraph() ? Scene.v().getCallGraph() : null;
    // The body and method of this intra-procedural analysis
    private final Body body;
    private final SootMethod method;
    // The information of all methods.
    private final Map<SootMethod, MethodInfo> globalMethodInfo;
    // The information of current method
    // The points-to set of each variable value of each statement in current method
    private Map<UniqueStmt, Map<Value, PointsToSet>> currProgramStates;
    // The map from points-to set to reference for current method,
    private Map<PointsToSet, Reference> currRefMap;
    // The final summary of current method
    private Summary currFinalSummary;
    // The maximal length of call string for approximation
    private final int maxCallStringLen;

    // Three data structures below are used for the initialization of uniqueStmt
    // It stores the overall count number of string of each statement
    private final Map<String, Integer> stmtStrCounter;
    // It stores the count id of string of each statement
    private final Map<Stmt, Integer> countedStmtCache;
    // It stores the generated UniqueStmt(in order to reduce repetitious object generation)
    private final Map<UniqueStmt, UniqueStmt> uniqueStmtCache;
    // It stores methods that have been visited in previous analysis,
    // in order to eliminate recursive invoke
    private final Set<SootMethod> visitedMethods;
    // It stores the methods that has been visited in this analysis,
    // in order to avoid infinite run in loop
    //private final Set<SootMethod> localVisitedMethods;

    /** Naive constructor */
    public PointsToAnalysis(Body body, Map<SootMethod, MethodInfo> globalMethodInfo,
                            int maxCallStringLen, Map<String, Integer> stmtStrCounter,
                            Map<Stmt, Integer> countedStmtCache,
                            Map<UniqueStmt, UniqueStmt> uniqueStmtCache,
                            Set<SootMethod> visitedMethods) {
        super(new ExceptionalUnitGraph(body));
        this.body = body;
        this.method = body.getMethod();
        this.globalMethodInfo = globalMethodInfo;

        this.maxCallStringLen = maxCallStringLen;
        this.stmtStrCounter = stmtStrCounter;
        this.countedStmtCache = countedStmtCache;
        this.uniqueStmtCache = uniqueStmtCache;
        this.visitedMethods = visitedMethods;
        //this.localVisitedMethods = new HashSet<>();

        // Sanity check
        assertNotNull(body);
        assertNotNull(globalMethodInfo);
        assertNotNull(visitedMethods);

        // Get the information of current method
        this.currProgramStates = this.globalMethodInfo.get(method).getProgramStates();
        this.currRefMap = this.globalMethodInfo.get(method).getRefMap();
        this.currFinalSummary = this.globalMethodInfo.get(method).getFinalSummary();
    }

    /**
     * Do forward dataflow analysis
     */
    public void doAnalysis() {
       logger.debug("Analyzing method {} for pointer analysis", method);
       super.doAnalysis();
       // Make a summary after the analysis has terminated
       sumUpMethod();
    }

    /**
     * Visit each node in CFG, use flow function to calculate out-set according to in-set.
     * @param in        in-set of map from variable to pts
     * @param unit      the current node to visit in CFG
     * @param out       out-set of map from variable to pts
     */
    @Override
    protected void flowThrough(Map<Value, PointsToSet> in, Unit unit, Map<Value, PointsToSet> out) {

        Stmt stmt = (Stmt) unit;
        assertNotNull(stmt);
        // Get the uniqueStmt of the current statement
        UniqueStmt uniqueStmt = generateUniqueStmt(stmt);
        assertNotNull(uniqueStmt);

        // Mark that this UniqueStmt has been visited
        currProgramStates.putIfAbsent(uniqueStmt, new HashMap<>());
        // (strong) Update in-set to currMethodSummary
        currProgramStates.get(uniqueStmt).clear();
        currProgramStates.get(uniqueStmt).putAll(in);

        // Call different methods to deal with this node.
        // 1. The statement is an identity statement at the start of this method
        if (stmt instanceof JIdentityStmt) {
            visitIdentity(uniqueStmt);
        }

        // 2. The stmt is an assignment statement
        if (stmt instanceof AssignStmt) {
            AssignStmt assignStmt = (AssignStmt) stmt;
            Value rightOp = assignStmt.getRightOp();
            assertNotNull(rightOp);
            // Check the type of right operand
            // 1. The statement is a new statement
            if (rightOp instanceof JNewExpr) {
                visitNew(uniqueStmt);
            }
            // 2. The statement is a new array statement
            else if (rightOp instanceof JNewArrayExpr) {
                visitNewArray(uniqueStmt);
            }
            // 3. The statement is a normal assignment statement
            else {
                visitNormalAssign(uniqueStmt);
            }
        }

        // 3. The statement is an invoke statement
        // We pass the points-to set of this object, return value and parameters
        if (stmt instanceof InvokeStmt) {
            InvokeExpr invoke = stmt.getInvokeExpr();
            assertNotNull(invoke);
            assert(invoke instanceof InstanceInvokeExpr);
            visitInvoke(uniqueStmt, invoke);
        }

        // 4. The statement is a throw/return statement
        // Record the throw/return value in specialVarList
        if (stmt instanceof JThrowStmt
                || stmt instanceof ReturnStmt || stmt instanceof ReturnVoidStmt) {
            visitReturn(uniqueStmt);
        }

        // At last, we set out-set from currMethodSummary
        out.clear();
        out.putAll(currProgramStates.get(uniqueStmt));
    }

    /**
     * Visit an identity statement.
     * we will copy the context into the summary of this object and parameters.
     * @param uniqueStmt        the current identity statement
     */
    private void visitIdentity(UniqueStmt uniqueStmt) {
        // Precondition check
        Stmt stmt = uniqueStmt.getStmt();
        assert(stmt instanceof JIdentityStmt);

        // The right value is this object or parameter
        // The left value is the variable that refers to the right value
        JIdentityStmt idStmt = (JIdentityStmt) stmt;
        Value leftVal = idStmt.getLeftOpBox().getValue();
        Value rightVal = idStmt.getRightOpBox().getValue();

        // Check whether rightVal represents this object or parameter,
        // and we will copy its corresponding summary in context to currMethodSummary.
        // Also, we will record the left value in specialVarList to show
        // which variable refers to this object/parameter.

        // 1. rightVal represents this object
        if (rightVal instanceof ThisRef) {
            PointsToSet thisPts = currFinalSummary.getPointsToSet(0);
            // Record left value's new points-to set
            currProgramStates.get(uniqueStmt).put(leftVal, thisPts);
            // Record left value as this object in this method
            currFinalSummary.setValue(0, leftVal);
            // Record the reference to the points-to set
            Reference ref = new Reference(0);
            currRefMap.putIfAbsent(thisPts, ref);
        }
        // 2. rightVal represents parameter, and rightVal is an object/array reference
        else if (rightVal instanceof ParameterRef &&
                (rightVal.getType() instanceof RefType
                        || rightVal.getType() instanceof ArrayType)) {
            ParameterRef param = (ParameterRef) rightVal;
            // Get the index number of this parameter(the index of the first parameter is 0).
            int paramIndex = param.getIndex();
            PointsToSet paramPts = currFinalSummary.getPointsToSet(1);
            // Record left value's new points-to set
            currProgramStates.get(uniqueStmt).put(leftVal, paramPts);
            // Record left value as the index th parameter in this method
            currFinalSummary.setValue(paramIndex + 2, leftVal);
            // Record the reference to the points-to set
            Reference ref = new Reference(paramIndex + 2);
            currRefMap.putIfAbsent(paramPts, ref);
        }
        // 3. rightVal represents exception, we do nothing here.
        else if (rightVal instanceof JCaughtExceptionRef) {
            //PointsToSet
            currProgramStates.get(uniqueStmt).put(leftVal, null);
        }
    }

    /**
     * Visit a new statement for object.
     * we will initialize the points-to set as null
     * and record it into currMethodSummary.
     * @param uniqueStmt    the current new statement
     */
    private void visitNew(UniqueStmt uniqueStmt) {
        // Precondition check
        Stmt stmt = uniqueStmt.getStmt();
        assert(stmt instanceof AssignStmt);

        // Get the current new(init) statement
        AssignStmt assignStmt = (AssignStmt) stmt;
        // Get the variable that refers to the new object
        Value leftOp = assignStmt.getLeftOp();

        // Set its points-to set as empty
        currProgramStates.get(uniqueStmt).put(leftOp, null);
    }

    /**
     * Visit a new(init) statement for array.
     * we will initialize the points-to set as null.
     * @param uniqueStmt    the current newArray statement
     */
    private void visitNewArray(UniqueStmt uniqueStmt) {
        // Precondition check
        Stmt stmt = uniqueStmt.getStmt();
        assert(stmt instanceof AssignStmt);

        // Get the current new(init) statement
        AssignStmt assignStmt = (AssignStmt) stmt;
        // Get the variable that refers to the new object
        Value leftVal = assignStmt.getLeftOp();
        // The type of leftVal must be an array type
        Type leftOpType = leftVal.getType();
        assert(leftOpType instanceof ArrayType);
        Context context = new Context(maxCallStringLen, uniqueStmt);
        // Allocate a new location for the array reference
        ArrRefPointsToSet pts = new ArrRefPointsToSet(context, leftOpType);

        // The element type of leftVal
        Type leftOpEleType = ((ArrayType) leftVal.getType()).getArrayElementType();
        // Allocate a heap location for element in array
        PointsToSet elePts;
        if (leftOpEleType instanceof RefType) {
            elePts = new ObjRefPointsToSet(context, leftOpEleType);
        } else if (leftOpEleType instanceof ArrayType) {
            elePts = new ArrRefPointsToSet(context, leftOpEleType);
        } else {
            elePts = null;
        }
        // Record the points-to set of leftVal into currMethodSummary
        pts.setElePts(elePts);
        // We don't need to cover the identical pts
        if (currProgramStates.get(uniqueStmt).containsKey(leftVal)) {
            PointsToSet oldPts = currProgramStates.get(uniqueStmt).get(leftVal);
            if (pts.equals(oldPts)) {
                pts = (ArrRefPointsToSet) oldPts;
            }
        }
        currProgramStates.get(uniqueStmt).put(leftVal, pts);
        // Record left value as the index th element in finalSummary
        int index = currFinalSummary.addEmptyVariable();
        currFinalSummary.setValue(index, leftVal);
        // Record the reference to the points-to set
        Reference ref = new Reference(index);
        currRefMap.putIfAbsent(pts, ref);
    }

    /**
     * Visit a normal assignment statement.
     * We will update the points-to set for a variable.
     * @param uniqueStmt    the current normal assignment statement
     */
    private void visitNormalAssign(UniqueStmt uniqueStmt) {
        // Precondition check
        Stmt stmt = uniqueStmt.getStmt();
        assert(stmt instanceof AssignStmt);

        // Get the current new(init) statement
        AssignStmt assignStmt = (AssignStmt) stmt;
        // Get the variable of left and right operands
        Value leftVal = assignStmt.getLeftOp();
        Value rightVal = assignStmt.getRightOp();
        assertNotNull(leftVal);
        assertNotNull(rightVal);

        // Get the type of left and right value
        Type leftValType = null;
        Type rightValType = null;
        // Special case for cast expression
        if (rightVal instanceof JCastExpr) {
            leftValType = ((JCastExpr) rightVal).getType();
            rightVal = ((JCastExpr) rightVal).getOpBox().getValue();
            rightValType = rightVal.getType();
        }

        // Check whether it is a normal assign statement or an invoke assign statement
        // 1. the statement is an invoke assign statement(e.g. r = a.m(b1,b2))
        // we call visitInvoke() to deal with it
        if (stmt.containsInvokeExpr()) {
            InvokeExpr invoke = stmt.getInvokeExpr();
            visitInvoke(uniqueStmt, invoke);
        }
        // 2. the statement is a normal assign statement(e.g. r = a)
        // and right value is not a primitive type.
        // we need to get the points-to set of right value
        // and assign it to that of left value
        else if (rightVal.getType() instanceof RefLikeType) {

            // there are five types of assignments:
            // 1. a = b
            // 2. a.f = b
            // 3. a = b.f
            // 4. a[i] = b
            // 5. a = b[i]
            // we assume that the pointsToSet of right value is stored in currMethodSummary

            // get the points-to set of right value based on different cases of right value
            PointsToSet rightValPts;
            // 1. right value is a reference to a field(e.g. b.f),
            // we should get the points-to set of that field
            if (rightVal instanceof JInstanceFieldRef) {
                // the base and field of right value
                Value rightValBase = ((JInstanceFieldRef) rightVal).getBase();
                SootField rightValField = ((JInstanceFieldRef) rightVal).getFieldRef().resolve();
                assertNotNull(rightValBase);
                assertNotNull(rightValField);
                // the points-to set of the base of right value
                PointsToSet rightValBasePts = currProgramStates.get(uniqueStmt).get(rightValBase);
                // the points-to set of base of right value may not be recorded in currMethodSummary
                if (rightValBasePts == null) {
                    rightValPts = null;
                } else {
                    assert(rightValBasePts instanceof ObjRefPointsToSet);
                    rightValPts = ((ObjRefPointsToSet) rightValBasePts).getFieldPts(rightValField);
                    // record the reference of that field
                    if (!currRefMap.containsKey(rightValPts) &&
                            currRefMap.containsKey(rightValBasePts)) {
                        Reference rightValRef = new Reference(currRefMap.get(rightValBasePts));
                        rightValRef.addField(rightValField);
                        currRefMap.put(rightValPts, rightValRef);
                    }
                }
            }
            // 2. right value is a reference to an array(e.g. b[i]),
            // we should get the points-to set of the element reference
            else if (rightVal instanceof JArrayRef) {
                // the reference of array of right value and its corresponding points-to set
                Value rightValArrRef = ((JArrayRef) rightVal).getBaseBox().getValue();
                PointsToSet rightValArrRefPts = currProgramStates.get(uniqueStmt).get(rightValArrRef);
                // the points-to set of reference array of right value
                // may not be recorded in currMethodSummary
                if (rightValArrRefPts == null) {
                    rightValPts = null;
                } else {
                    assert(rightValArrRefPts instanceof ArrRefPointsToSet);
                    rightValPts = ((ArrRefPointsToSet) rightValArrRefPts).getElePts();
                    // record the reference of that element
                    if (!currRefMap.containsKey(rightValPts) &&
                            currRefMap.containsKey(rightValArrRefPts)) {
                        Reference rightValRef = new Reference(currRefMap.get(rightValArrRefPts));
                        rightValRef.addField(null);
                        currRefMap.put(rightValPts, rightValRef);
                    }
                }
            }
            // 3. right value is just an object(e.g. b)
            // just get its points-to set from currMethodSummary
            else if (rightVal instanceof JimpleLocal) {
                if (currProgramStates.get(uniqueStmt).containsKey(rightVal)) {
                    rightValPts = currProgramStates.get(uniqueStmt).get(rightVal);
                } else {
                    rightValPts = null;
                }
            }
            // 4. other cases
            else {
                rightValPts = null;
            }

            // update the points-to set of left value, based on different cases of left value
            // 1. left value is a reference to a field(e.g. a.f),
            // we should update the points-to set of that field
            if (leftVal instanceof JInstanceFieldRef) {
                // the base and field of left value
                Value leftValBase = ((JInstanceFieldRef) leftVal).getBase();
                SootField leftValField = ((JInstanceFieldRef) leftVal).getFieldRef().resolve();
                // we should avoid analyzing recursive data structures
                if (leftValBase == rightVal) {
                    return;
                }
                // get the points-to set of base object of left value
                PointsToSet leftValBasePts = null;
                if (currProgramStates.get(uniqueStmt).containsKey(leftValBase)) {
                    leftValBasePts = currProgramStates.get(uniqueStmt).get(leftValBase);
                }
                if (leftValBasePts == null) {
                    leftValBasePts = new ObjRefPointsToSet();
                }
                // (strong) update the points-to set of the field
                ((ObjRefPointsToSet) leftValBasePts).addField(leftValField, rightValPts);
            }
            // 2. left value is a reference to an array(e.g. a[i])
            // we should update the points-to set of the element
            else if (leftVal instanceof JArrayRef) {
                // the reference of array of left value and its corresponding points-to set
                Value leftValArrRef = ((JArrayRef) leftVal).getBaseBox().getValue();
                PointsToSet leftValArrRefPts = currProgramStates.get(uniqueStmt).get(leftValArrRef);
                // we should avoid analyzing recursive data structures
                if (leftValArrRef == rightVal) {
                    return;
                }
                // (strong) update the points-to set of the element in array
                assert(leftValArrRefPts instanceof ArrRefPointsToSet);
                if (leftValArrRefPts != null) {
                    ((ArrRefPointsToSet) leftValArrRefPts).setElePts(rightValPts);
                }
            }
            // 3. left operand is just an object(e.g. a),
            // we should update its points-to set
            else {
                if (leftValType instanceof ArrayType
                        && rightValType instanceof RefType) {
                    if (rightValPts != null) {
                        ArrRefPointsToSet convertedPts
                                = new ArrRefPointsToSet((ObjRefPointsToSet) rightValPts, leftValType);
                        rightValPts = convertedPts;
                    }
                }
                // Strong update: use pts(rightVal) to replace pts(leftVal)
                currProgramStates.get(uniqueStmt).put(leftVal, rightValPts);
            }
        }
    }

    /**
     * Visit an init invoke statement of object/array.
     * And we will allocate a location for that object/array.
     * If newObject is Base, then stmt is invokeStmt.
     * If newObject is RetVal, then stmt is assignStmt.
     * @param uniqueStmt    the current new(init) statement
     * @param newVar        the new object to be allocated,
     *                      it can be a base object or a return value
     * @param returnType    the return type of the method
     * @param isInit        whether we are initializing an object
     */
    private void visitAlloc(UniqueStmt uniqueStmt, Value newVar, Type returnType, boolean isInit) {
        // newValue can only be a reference to an object or an array
        // The type of allocated points-to set
        Type refType;
        if (!isInit) {
            refType = returnType;
        } else {
            refType = newVar.getType();
        }
        assert(refType instanceof RefType || refType instanceof ArrayType);

        // Allocate a new heap location and get the pointsToSet for newObject
        Context context = new Context(maxCallStringLen, uniqueStmt);
        PointsToSet newPts;
        if (refType instanceof RefType) {
            newPts = new ObjRefPointsToSet(context, refType);
        } else {
            newPts = new ArrRefPointsToSet(context, refType);
        }

        // We don't need to cover the identical pts
        if (currProgramStates.get(uniqueStmt).containsKey(newVar)) {
            PointsToSet oldPts = currProgramStates.get(uniqueStmt).get(newVar);
            if (newPts.equals(oldPts)) {
                newPts = oldPts;
            }
        }

        // Strong update: use points-to set to replace the original points-to set of newVar
        currProgramStates.get(uniqueStmt).put(newVar, newPts);
        // Record left value as the index th element in finalSummary
        int index = currFinalSummary.addEmptyVariable();
        currFinalSummary.setValue(index, newVar);
        // Record the reference to the points-to set
        Reference ref = new Reference(index);
        currRefMap.putIfAbsent(newPts, ref);
    }

    /**
     * Visit an invoke statement,
     * pass the points-to set of this object and arguments from this summary to callee summary,
     * also pass the points-to set of this object,
     * return value and arguments from callee summary to this summary
     * @param uniqueStmt    the current invoke statement
     * @param invoke        the current invoke expression
     */
    private void visitInvoke(UniqueStmt uniqueStmt, InvokeExpr invoke) {
        Stmt stmt = uniqueStmt.getStmt();
        // Get callee method by using call graph to cope with polymorphism
        SootMethod calleeMethod = getPreciseCalleeMethod(uniqueStmt, invoke);

        // We avoid analyzing a callee repetitiously in a method
        //if (localVisitedMethods.contains(calleeMethod)) {
        //    return;
        //}
        // Get the base object of this invocation in caller (if applies)
        Value base = null;
        if (invoke instanceof InstanceInvokeExpr) {
            base = ((InstanceInvokeExpr) invoke).getBase();
        }

        // Get the return value of this invocation for future use
        Value retVal = null;
        if (stmt instanceof AssignStmt) {
            retVal = ((AssignStmt) stmt).getLeftOp();
        }

        // Check whether this method has active body and can be analyzed
        // 1. Analyze callee method directly(if possible)
        // We use visitedMethod to check whether the invoke is recursive
        // We don't analyze recursive call
        if (!isObjectInitMethod(calleeMethod) && calleeMethod.hasActiveBody()
                && !visitedMethods.contains(calleeMethod)) {
            // Record the visit to the callee method
            //localVisitedMethods.add(calleeMethod);
            // Check whether the callee method has been analyzed
            // Callee method has not been analyzed,
            // we will analyze it and build a summary
            if (!globalMethodInfo.containsKey(calleeMethod)) {

                // Create a new summary for callee method
                int argNum = calleeMethod.getParameterCount();
                Summary newFinalSummary = new Summary(argNum + 2);

                // pass the points-to set of this object and arguments from this summary to callee summary
                // 1. index 0 means summary[0] = points-to set of base(this) object
                // for non-static invoke, where base is not null
                if (base != null) {
                    newFinalSummary.setPointsToSet(0, currProgramStates.get(uniqueStmt).get(base));
                }

                // 3. index i+2 means summary[i+2] is points-to set of the i th argument
                for (int i = 0; i < argNum; ++i) {
                    Value arg = invoke.getArg(i);
                    if (arg.getType() instanceof RefLikeType) {
                        if (currProgramStates.get(uniqueStmt).containsKey(arg)) {
                            PointsToSet argPts = currProgramStates.get(uniqueStmt).get(arg);
                            newFinalSummary.setPointsToSet(i + 2, argPts);
                        }
                    }
                }
                // Update methodSummary for calleeMethod
                globalMethodInfo.putIfAbsent(calleeMethod, new MethodInfo(newFinalSummary));
                // Do a top-down analysis
                Body calleeBody = calleeMethod.retrieveActiveBody();
                assertNotNull(calleeBody);
                visitedMethods.add(calleeMethod);
                PointsToAnalysis calleeAnalysis = new PointsToAnalysis(calleeBody,
                        globalMethodInfo, maxCallStringLen, stmtStrCounter,
                        countedStmtCache, uniqueStmtCache, visitedMethods);
                calleeAnalysis.doAnalysis();
                visitedMethods.remove(calleeMethod);

                // Mark that the callee method has been analyzed
                globalMethodInfo.get(calleeMethod).setAsAnalyzed();
            }

            // Pass the points-to set of this object, return value and arguments
            // from final summary of callee to this program state

            // Get the information of the callee method
            MethodInfo calleeMethodInfo = globalMethodInfo.get(calleeMethod);
            // Get the final state of callee
            UniqueStmt calleeFinalStmt = calleeMethodInfo.getFinalStmt();
            Map<Value, PointsToSet> calleeFinalProgramState =
                    calleeMethodInfo.getProgramStates().get(calleeFinalStmt);
            Summary calleeFinalSummary = calleeMethodInfo.getFinalSummary();
            Map<PointsToSet, Reference> calleeRefMap = calleeMethodInfo.getRefMap();

            int calleeSpecialVarNum = calleeFinalSummary.getSpecialVarNum();

            for (int i = 0; i < calleeSpecialVarNum; ++i) {
                // Get caller value whose points-to set needs to be updated
                Value callerValue;
                if (i == 0) {
                    callerValue = base;
                } else if (i == 1) {
                    callerValue = retVal;
                } else {
                    callerValue = invoke.getArg(i - 2);
                }
                // Get the corresponding special variable in callee
                Value calleeValue = calleeFinalSummary.getValue(i);
                PointsToType calleePtt = calleeFinalSummary.getPointsToType(i);
                PointsToSet newPts;
                // We only update points-to set for reference variables
                if (calleeValue != null && calleeValue.getType() instanceof RefLikeType) {
                    // 1. Get alias points-to set
                    if (calleePtt == PointsToType.Sub) {
                        Reference calleeRef = calleeFinalSummary.getReference(i);
                        int calleeRefIndex = calleeRef.getBaseIndex();
                        // We don't need to do such substitution
                        if (calleeRefIndex == i) {
                            continue;
                        }
                        List<SootField> accessPath = calleeRef.getAccessPath();
                        // Get the aliased value in caller
                        Value aliasValue = null;
                        if (calleeRefIndex == 0) {
                            aliasValue = base;
                        } else if (calleeRefIndex == 1) {
                            aliasValue = retVal;
                        } else if (calleeRefIndex != -1){
                            assert(calleeRefIndex < calleeSpecialVarNum);
                            aliasValue = invoke.getArg(calleeRefIndex - 2);
                        }
                        // Get the base points-to set of alias value
                        PointsToSet newBasePts = currProgramStates.get(uniqueStmt).get(aliasValue);
                        // Get the new points-to set to be substituted
                        if (newBasePts != null) {
                            newPts = newBasePts.getFieldOrElePts(accessPath);
                        } else {
                            newPts = null;
                        }
                        int baseIndex = currFinalSummary.getIndexOfValue(aliasValue);
                        Reference newRef = new Reference(baseIndex, accessPath);
                        // Record the new reference info at caller side
                        currRefMap.put(newPts, newRef);
                        // Record the new points-to info at caller side
                        currProgramStates.get(uniqueStmt).put(callerValue, newPts);
                    }
                    // 2. Get a new allocated points-to set
                    else if (calleePtt == PointsToType.New) {
                        // Get the new points-to set in callee
                        PointsToSet calleePts = calleeFinalSummary.getPointsToSet(i);
                        if (calleePts instanceof ObjRefPointsToSet) {
                            newPts = new ObjRefPointsToSet((ObjRefPointsToSet) calleePts);
                        } else {
                            newPts = new ArrRefPointsToSet((ArrRefPointsToSet) calleePts);
                        }
                        // Add current invoke statement into context
                        newPts.addContext(uniqueStmt);
                        // Adjust the type of location to callerValue's type for init
                        if (isInitMethod(calleeMethod)) {
                            newPts.setLocationType(callerValue.getType());
                        }
                        // We don't need to cover the identical pts
                        if (currProgramStates.get(uniqueStmt).containsKey(callerValue)) {
                            PointsToSet oldPts = currProgramStates.get(uniqueStmt).get(callerValue);
                            if (newPts.equals(oldPts)) {
                                newPts = oldPts;
                            }
                        }
                        // Record the new points-to info
                        currProgramStates.get(uniqueStmt).put(callerValue, newPts);
                        // Record new  value as the index th element in finalSummary
                        int index = currFinalSummary.addEmptyVariable();
                        currFinalSummary.setValue(index, callerValue);
                        // Record the reference to the points-to set
                        Reference ref = new Reference(index);
                        currRefMap.putIfAbsent(newPts, ref);
                    }
                }
            }
        }
        // 2. for invoke to Object init method
        // we don't analyze the method, but we only allocate a space for base object
        else if (isInitMethod(calleeMethod)) {
            // Record the visit to the callee method
            //localVisitedMethods.add(calleeMethod);
            visitAlloc(uniqueStmt, base, calleeMethod.getReturnType(), true);
        }
        // 3. for invoke to a library method that returns a reference type
        // we don't analyze the method, but we should allocate the space for return value
        else if (retVal != null && calleeMethod.getReturnType() instanceof RefLikeType) {
            // Record the visit to the callee method
            //localVisitedMethods.add(calleeMethod);
            visitAlloc(uniqueStmt, retVal, calleeMethod.getReturnType(), false);
        }
    }

    /**
     * Visit a return statement
     * Set this statement sa the finalStmt in this method
     * Record the return value in specialVarList
     * @param uniqueStmt    the current return statement
     */
    private void visitReturn(UniqueStmt uniqueStmt) {
        Stmt stmt = uniqueStmt.getStmt();
        assert(stmt instanceof JThrowStmt
                || stmt instanceof ReturnStmt || stmt instanceof ReturnVoidStmt);
        // record the final statement in this method
        // (note that there is only one return statement in each method body)
        globalMethodInfo.get(method).setFinalStmt(uniqueStmt);

        // record the return value in specialVarList
        // if it is not of primitive type
        if(stmt instanceof ReturnStmt) {
            ReturnStmt returnStmt = (ReturnStmt) stmt;
            Value retVal = returnStmt.getOpBox().getValue();
            if (retVal.getType() instanceof RefLikeType) {
                // record retVal in finalSummary
                currFinalSummary.setValue(1, retVal);
                // record the reference to the points-to set
                PointsToSet pts = currProgramStates.get(uniqueStmt).get(retVal);
                if (pts != null) {
                    Reference ref = new Reference(1);
                    currRefMap.putIfAbsent(pts, ref);
                }
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
     * Merge method as a meet operator, which is intersection for maps
     * @param in1       one in-set of points-to map
     * @param in2       another in-set of points-to map
     * @param out       the out-set of points-to map
     */
    @Override
    protected void merge(Map<Value, PointsToSet> in1, Map<Value, PointsToSet> in2,
                         Map<Value, PointsToSet> out) {
        out.clear();
        out.putAll(in1);
        // Merge the pointsToSet of each variable in out with in1
        for (Map.Entry<Value, PointsToSet> entry : in2.entrySet()) {
            Value variable = entry.getKey();
            PointsToSet pts = entry.getValue();
            if (out.containsKey(variable)) {
                if (out.get(variable) == null) {
                    out.put(variable, pts);
                } else if (!out.get(variable).equals(pts)) {
                    out.put(variable, null);
                }
            } else {
                out.put(variable, pts);
            }
        }
    }

    /**
     * Copy from source to destination.
     * @param source        the source points-to map
     * @param dest          the destination points-to map
     */
    @Override
    protected void copy(Map<Value, PointsToSet> source, Map<Value, PointsToSet> dest) {
        dest.clear();
        dest.putAll(source);
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
            // 2. The string of that statement hasn't been counted
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

    /** Check whether the given method is an init method */
    private boolean isInitMethod(SootMethod method) {
        return method.getName().equals("<init>");
    }

    /** Check whether the given method is an Object init method */
    private boolean isObjectInitMethod(SootMethod method) {
        return method.getSignature().equals("<java.lang.Object: void <init>()>");
    }

    /**
     * Make a summary of this method after analysis
     * For each special variable, set its reference or points-to set
     */
    private void sumUpMethod() {
        // The information of callee method
        MethodInfo currMethodInfo = globalMethodInfo.get(method);
        Summary currFinalSummary = currMethodInfo.getFinalSummary();
        UniqueStmt currFinalStmt = currMethodInfo.getFinalStmt();
        Map<PointsToSet, Reference> calleeRefMap = currMethodInfo.getRefMap();
        // Sanity check
        if (currMethodInfo == null || currFinalSummary == null
                || currFinalStmt == null || calleeRefMap == null) {
            return;
        }

        Map<Value, PointsToSet> calleeFinalPointsToMap =
                currMethodInfo.getProgramStates().get(currFinalStmt);
        // get summary for this object, return value and arguments.
        // number of special variables in callee method, which equals to argNum + 2
        int specialValNum = currFinalSummary.getSpecialVarNum();
        for (int i = 0; i < specialValNum; ++i) {
            // Get the value, points-to set and the corresponding reference
            Value value = currFinalSummary.getValue(i);
            if (value == null) {
                continue;
            }
            PointsToSet pts = calleeFinalPointsToMap.get(value);
            Reference ref = null;
            if (pts != null) {
                ref = calleeRefMap.get(pts);
            }

            // Set reference or points-to set for the variable
            // 1. This variable should be substituted by other variable
            if(ref != null && ref.getBaseIndex() < specialValNum) {
                currFinalSummary.setReference(i, ref);
                currFinalSummary.setPointsToType(i, PointsToType.Sub);
            }
            // 2. This variable should point to a new allocated location
            else if (pts != null) {
                currFinalSummary.setPointsToSet(i, pts);
                currFinalSummary.setPointsToType(i, PointsToType.New);
            }
        }
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
            PointsToSet basePts = currProgramStates.get(uniqueStmt).get(base);
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
