package taintAnalysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.Stmt;
import taintAnalysis.pointsToAnalysis.AbstractLoc;
import taintAnalysis.pointsToAnalysis.PointsToAnalysis;
import taintAnalysis.pointsToAnalysis.Context;
import taintAnalysis.sourceSinkManager.ISourceSinkManager;
import taintAnalysis.taintWrapper.ITaintWrapper;

import java.util.*;
import java.util.List;

public class InterTaintAnalysis {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ISourceSinkManager sourceSinkManager;
    private final ITaintWrapper taintWrapper;
    private final Set<Taint> sources;
    private final Set<Taint> sinks;
    // method summary for taint analysis
    private final Map<SootMethod, Map<Taint, List<Set<Taint>>>> taintMethodSummary;
    private final Map<SootMethod, Map<Taint, Taint>> methodTaintCache;
    // method summary for points-to analysis
    private final Map<SootMethod, Map<Context, Map<UniqueStmt, Map<Value,Set<AbstractLoc>>>>> pointsToMethodSummary;
    private final Map<SootMethod, Map<Context, List<Set<AbstractLoc>>>> finalMethodSummary;
    // whether to use points-to analysis
    private final boolean use_points_to;

    public InterTaintAnalysis(ISourceSinkManager sourceSinkManager, ITaintWrapper taintWrapper, boolean use_points_to) {
        this.sourceSinkManager = sourceSinkManager;
        this.taintWrapper = taintWrapper;
        this.sources = new HashSet<>();
        this.sinks = new HashSet<>();
        this.taintMethodSummary = new HashMap<>();
        this.methodTaintCache = new HashMap<>();
        this.pointsToMethodSummary = new HashMap<>();
        this.finalMethodSummary = new HashMap<>();
        this.use_points_to = use_points_to;
    }

    public void doAnalysis() {
        this.sources.clear();
        this.sinks.clear();
        this.taintMethodSummary.clear();
        this.methodTaintCache.clear();
        this.pointsToMethodSummary.clear();
        this.finalMethodSummary.clear();

        List<SootMethod> methodList = new ArrayList<>();
        for (SootClass sc : Scene.v().getApplicationClasses()) {
            for (SootMethod sm : sc.getMethods()) {
                if (sm.isConcrete()) {
                    methodList.add(sm);
                }
            }
        }
        methodList.sort(Comparator.comparing(SootMethod::toString));

        logger.info("Num of methods: {}", methodList.size());

        // the data structures for UniqueStmt in inter-procedural analysis
        Map<String,Integer> stmtStrCounter = new HashMap<>();
        Map<Stmt, Integer> countedStmtCache = new HashMap<>();
        Map<UniqueStmt, UniqueStmt> uniqueStmtCache = new HashMap<>();

        // Bootstrap for points-to analysis
        if (use_points_to) {
            startPointsToAnalysis(methodList, stmtStrCounter, countedStmtCache, uniqueStmtCache);
        }
        // Bootstrap for taint analysis
        //startTaintAnalysis(methodList, stmtStrCounter, countedStmtCache, uniqueStmtCache);
    }

    /**
     * Bootstrap for points-to analysis
     * @param methodList            the list of method that we need to analyze
     * @param stmtStrCounter        stores the overall count number of string of each statement
     * @param countedStmtCache      stores the count id of string of each statement
     * @param uniqueStmtCache       stores the generated UniqueStmt(in order to reduce repetitious object generation)
     */
    private void startPointsToAnalysis(List<SootMethod> methodList, Map<String,Integer> stmtStrCounter,
                                       Map<Stmt, Integer> countedStmtCache, Map<UniqueStmt, UniqueStmt> uniqueStmtCache) {
        logger.info("Start points-to analysis");
        int callStringLen = 5;
        // get the body of each method
        List<Body> bodyList = new ArrayList<>();
        for (SootMethod sm : methodList) {
            Body b = sm.retrieveActiveBody();
            bodyList.add(b);
        }

        // only analyze main() method as an entry method
        // if there is an invoke statement, we then recursively analyze callee method
        for (Body b : bodyList) {
            if (b.getMethod().toString().contains("void main(java.lang.String[])")) {
                int argNum = b.getMethod().getParameterCount();
                Context context = new Context(callStringLen, new LinkedList<>(), argNum);
                PointsToAnalysis analysis = new PointsToAnalysis(b, context, pointsToMethodSummary,
                        finalMethodSummary, stmtStrCounter, countedStmtCache, uniqueStmtCache, new HashSet<>());
                analysis.doAnalysis();
            }
        }

        logger.info("Finished points-to analysis");
    }

    /**
     * Bootstrap for taint analysis
     * @param methodList            the list of method that we need to analyze
     * @param stmtStrCounter        stores the overall count number of string of each statement
     * @param countedStmtCache      stores the count id of string of each statement
     * @param uniqueStmtCache       stores the generated UniqueStmt(in order to reduce repetitious object generation)
     */
    private void startTaintAnalysis(List<SootMethod> methodList, Map<String,Integer> stmtStrCounter,
                                    Map<Stmt, Integer> countedStmtCache, Map<UniqueStmt, UniqueStmt> uniqueStmtCache) {
        int iter = 1;
        logger.info("iter {} in taint analysis", iter);
        List<Body> bodyList = new ArrayList<>();
        for (SootMethod sm : methodList) {
            Body b = sm.retrieveActiveBody();
            bodyList.add(b);
        }
        for (Body b : bodyList) {
            TaintFlowAnalysis analysis = new TaintFlowAnalysis(b, sourceSinkManager, Taint.getEmptyTaint(),
                    taintMethodSummary, methodTaintCache, taintWrapper, stmtStrCounter, countedStmtCache, uniqueStmtCache);
            analysis.doAnalysis();
            sources.addAll(analysis.getSources());
        }
        iter++;

        boolean changed = true;
        while (changed) {
            changed = false;
            logger.info("iter {} in taint analysis", iter);

            for (SootMethod sm : methodList) {
                Body b = sm.retrieveActiveBody();
                Set<Taint> entryTaints = new HashSet<>();
                entryTaints.addAll(taintMethodSummary.get(sm).keySet());
                for (Taint entryTaint : entryTaints) {
                    TaintFlowAnalysis analysis = new TaintFlowAnalysis(b, sourceSinkManager, entryTaint,
                            taintMethodSummary, methodTaintCache, taintWrapper, stmtStrCounter, countedStmtCache, uniqueStmtCache);
                    analysis.doAnalysis();
                    sinks.addAll(analysis.getSinks());
                    changed |= analysis.isChanged();
                }
            }

            iter++;
        }

        logger.info("Found {} sinks reached from {} sources", sinks.size(), sources.size());
    }

    public List<Taint> getSources() {
        List<Taint> lst = new ArrayList<>();
        lst.addAll(sources);
        return lst;
    }

    public List<Taint> getSinks() {
        List<Taint> lst = new ArrayList<>();
        lst.addAll(sinks);
        return lst;
    }

    public Map<SootMethod, Map<Taint, List<Set<Taint>>>> getMethodSummary() {
        return taintMethodSummary;
    }

    public Map<SootMethod, Map<Taint, Taint>> getMethodTaintCache() {
        return methodTaintCache;
    }

    public Map<SootMethod, Map<Context, Map<UniqueStmt, Map<Value,Set<AbstractLoc>>>>> getPointsToMethodSummary() {
        return pointsToMethodSummary;
    }

    public Map<SootMethod, Map<Context, List<Set<AbstractLoc>>>> getFinalMethodSummary() {
        return finalMethodSummary;
    }

}
