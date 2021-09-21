package taintAnalysis.pointsToAnalysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.AssignStmt;
import soot.jimple.internal.JNewExpr;
import soot.jimple.Stmt;
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
    // the maximal number of nearest call statements
    // or that is k in k-limiting callString
    private final int callStringLen;
    // the string of call statements as a context
    private final Queue<UniqueStmt> callString;
    // the summary for all methods
    // method, context, statement, variable -> set of abstractLoc
    private final Map<SootMethod, Map<Queue<UniqueStmt>, Map<UniqueStmt, Map<Value,Set<AbstractLoc>>>>> methodSummary;
    // the summary for this method
    // context, statement, variable -> set of abstractLoc
    private final Map<Queue<UniqueStmt>, Map<UniqueStmt, Map<Value,Set<AbstractLoc>>>> currMethodSummary;

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
    public PointsToAnalysis(Body body, int callStringLen, Queue<UniqueStmt> callString,
                            Map<SootMethod, Map<Queue<UniqueStmt>, Map<UniqueStmt, Map<Value,Set<AbstractLoc>>>>> methodSummary,
                            Map<String, Integer> stmtStrCounter, Map<Stmt, Integer> countedStmtCache,
                            Map<UniqueStmt, UniqueStmt> uniqueStmtCache) {
        super(new ExceptionalUnitGraph(body));
        this.body = body;
        this.method = body.getMethod();
        this.phantomRetStmt = PhantomRetStmt.getInstance(method);

        this.callStringLen = callStringLen;
        this.callString = callString;
        this.methodSummary = methodSummary;
        this.stmtStrCounter = stmtStrCounter;
        this.countedStmtCache = countedStmtCache;
        this.uniqueStmtCache = uniqueStmtCache;

        // Sanity check
        assertNotNull(body);
        assertNotNull(callString);
        assertNotNull(methodSummary);

        // Initialize methodSummary for current method (if not done yet)
        methodSummary.putIfAbsent(method, new HashMap<>());
        this.currMethodSummary = methodSummary.get(method);

        // Initialize the pts summary for current method with the input callString (if not done yet)
        if (!this.currMethodSummary.containsKey(callString)) {
            changed = true;
            Map<UniqueStmt, Map<Value,Set<AbstractLoc>>> summary = new HashMap<>();
            this.currMethodSummary.put(callString, summary);
        }
    }

    /**
     * Do forward dataflow analysis
     */
    public void doAnalysis() {
       logger.debug("Analyzing method {} for pointer analysis", method);
       super.doAnalysis();
    }

    /**
     * isit each node in CFG, use flow function to calculate out-set according to in-set
     * @param in        in-set of map from variable to pts
     * @param unit      the current node to visit in CFG
     * @param out       out-set of map from variable to pts
     */
    @Override
    protected void flowThrough(Map<Value, Set<AbstractLoc>> in, Unit unit, Map<Value, Set<AbstractLoc>> out) {

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
     * Check whether the given uniqueStmt is a new statement
     * @param uniqueStmt    the given uniqueStmt to check
     * @return              true iff uniqueStmt is a new statement
     */
    private boolean checkNewStmt(UniqueStmt uniqueStmt) {
        if (uniqueStmt == null)
            return false;

        Stmt stmt = uniqueStmt.getStmt();
        if (stmt == null)
            return false;

        // stmt should be an assign statement, and for the right operator,
        // its value must be a JNewExpr
        if (stmt instanceof AssignStmt) {
            AssignStmt assignStmt = (AssignStmt) stmt;
            Value rightOp = assignStmt.getRightOp();
            if (rightOp instanceof JNewExpr)
                return true;
        }
        return false;
    }

}
