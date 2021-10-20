package taintAnalysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.Stmt;
import soot.jimple.internal.JInstanceFieldRef;
import taintAnalysis.pointsToAnalysis.MethodInfo;
import taintAnalysis.pointsToAnalysis.PointsToAnalysis;
import taintAnalysis.pointsToAnalysis.Summary;
import taintAnalysis.pointsToAnalysis.pointsToSet.PointsToSet;
import taintAnalysis.sourceSinkManager.ISourceSinkManager;
import taintAnalysis.taintWrapper.ITaintWrapper;

import java.util.*;
import java.util.List;

/**
 * The class to do the inter-procedural dataflow analysis
 */
public class InterTaintAnalysis {
    // The logger to record log information
    private final Logger logger = LoggerFactory.getLogger(getClass());
    // It checks whether a method is a source or sink
    private final ISourceSinkManager sourceSinkManager;
    // It transfers taints from some library methods,
    // which is an approximation to save time and memory
    private final ITaintWrapper taintWrapper;
    // The set of taints that is in source for all methods
    private final Set<Taint> sources;
    // The set of taints that has reached the sink for all methods
    private final Set<Taint> sinks;
    // The global program states for taint analysis,
    // which is a set of discovered taints in each method in each context
    private final Map<SootMethod, Map<Taint, List<Set<Taint>>>> taintMethodSummary;
    private final Map<SootMethod, Map<Taint, Taint>> methodTaintCache;
    // The global program states for points-to analysis,
    // which is a map from value to points-to set in each method
    private final Map<SootMethod, Map<UniqueStmt, Map<Value, PointsToSet>>> pointsToMethodSummary;
    // Whether to do points-to analysis
    private final boolean do_points_to;

    public InterTaintAnalysis(ISourceSinkManager sourceSinkManager, ITaintWrapper taintWrapper,
                              boolean do_points_to) {
        this.sourceSinkManager = sourceSinkManager;
        this.taintWrapper = taintWrapper;
        this.sources = new HashSet<>();
        this.sinks = new HashSet<>();
        this.taintMethodSummary = new HashMap<>();
        this.methodTaintCache = new HashMap<>();
        this.pointsToMethodSummary = new HashMap<>();
        this.do_points_to = do_points_to;
    }

    public void doAnalysis() {
        this.sources.clear();
        this.sinks.clear();
        this.taintMethodSummary.clear();
        this.methodTaintCache.clear();
        this.pointsToMethodSummary.clear();

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
        // stores the information of all analyzed methods
        Map<SootMethod, MethodInfo> globalMethodInfo = new HashMap<>();

        // do a pass of points-to analysis
        if (do_points_to) {
            startPointsToAnalysis(methodList, globalMethodInfo,
                    stmtStrCounter, countedStmtCache, uniqueStmtCache);
        }

        // Bootstrap for taint analysis
        startTaintAnalysis(methodList, globalMethodInfo, do_points_to,
                stmtStrCounter, countedStmtCache, uniqueStmtCache);
    }

    /**
     * Bootstrap for points-to analysis.
     * It uses a top-down method to do analysis from main methods to leaf methods.
     * Once a method has been analyzed, it builds a summary to avoid repetitious analysis
     * @param methodList            the list of method that we need to analyze
     * @param globalMethodInfo      the information of all methods
     * @param stmtStrCounter        stores the overall count number of string of each statement
     * @param countedStmtCache      stores the count id of string of each statement
     * @param uniqueStmtCache       stores the generated UniqueStmt(in order to reduce repetitious object generation)
     */
    private void startPointsToAnalysis(List<SootMethod> methodList,
                                       Map<SootMethod, MethodInfo> globalMethodInfo,
                                       Map<String,Integer> stmtStrCounter,
                                       Map<Stmt, Integer> countedStmtCache,
                                       Map<UniqueStmt, UniqueStmt> uniqueStmtCache) {
        logger.info("Start points-to analysis");
        // get the body of all methods
        List<Body> bodyList = new ArrayList<>();
        for (SootMethod sm : methodList) {
            Body b = sm.retrieveActiveBody();
            bodyList.add(b);
        }

        // The maximal length of call string in context is set default as 10
        int maxCallStringLen = 10;

        // Analyze main methods
        for (Body b : bodyList) {
            if (globalMethodInfo.containsKey(b.getMethod())) {
                continue;
            }
            int argNum = b.getMethod().getParameterCount();
            Summary summary = new Summary(argNum + 2);
            MethodInfo methodInfo = new MethodInfo(summary);
            globalMethodInfo.put(b.getMethod(), methodInfo);
            PointsToAnalysis analysis = new PointsToAnalysis(b, globalMethodInfo, maxCallStringLen,
                    stmtStrCounter, countedStmtCache, uniqueStmtCache, new HashSet<>());
            analysis.doAnalysis();
        }
        logger.info("Finished points-to analysis");
    }

    /**
     * Bootstrap for taint analysis.
     * It does analysis by analyzing each method in a worklist.
     * The analysis stops when no new taint is added.
     * @param methodList            the list of method that we need to analyze
     * @param globalMethodInfo      the information of all methods
     * @param use_points_to         whether using points-to analysis
     * @param stmtStrCounter        stores the overall count number of string of each statement
     * @param countedStmtCache      stores the count id of string of each statement
     * @param uniqueStmtCache       stores the generated UniqueStmt(in order to reduce repetitious object generation)
     */
    private void startTaintAnalysis(List<SootMethod> methodList,
                                    Map<SootMethod, MethodInfo> globalMethodInfo,
                                    boolean use_points_to,
                                    Map<String,Integer> stmtStrCounter,
                                    Map<Stmt, Integer> countedStmtCache,
                                    Map<UniqueStmt, UniqueStmt> uniqueStmtCache) {
        FieldUseChecker fieldUseChecker = new FieldUseChecker();
        // For debugging
        Set<Taint> mustNotUsedSinks = new HashSet<>();
        Set<Taint> mayUseSinks = new HashSet<>();
        Set<Taint> unknownSinks = new HashSet<>();

        Map<SootMethod, Map<JInstanceFieldRef, Integer>> globalSinkRefUseInfo = new HashMap<>();
        int iter = 1;
        logger.info("iter {} in taint analysis", iter);
        List<Body> bodyList = new ArrayList<>();
        for (SootMethod sm : methodList) {
            Body b = sm.retrieveActiveBody();
            bodyList.add(b);
        }
        for (Body b : bodyList) {
            TaintFlowAnalysis analysis = new TaintFlowAnalysis(b,
                    sourceSinkManager, Taint.getEmptyTaint(),
                    taintMethodSummary, methodTaintCache, taintWrapper,
                    use_points_to, globalMethodInfo, globalSinkRefUseInfo,
                    stmtStrCounter, countedStmtCache, uniqueStmtCache, fieldUseChecker);
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
                Set<Taint> entryTaints = new HashSet<>(taintMethodSummary.get(sm).keySet());
                for (Taint entryTaint : entryTaints) {
                    TaintFlowAnalysis analysis = new TaintFlowAnalysis(b,
                            sourceSinkManager, entryTaint,
                            taintMethodSummary, methodTaintCache, taintWrapper,
                            use_points_to, globalMethodInfo, globalSinkRefUseInfo,
                            stmtStrCounter, countedStmtCache, uniqueStmtCache, fieldUseChecker);
                    analysis.doAnalysis();
                    sinks.addAll(analysis.getSinks());
                    mustNotUsedSinks.addAll(analysis.mustNotUsedSinks);
                    mayUseSinks.addAll(analysis.mayUseSinks);
                    unknownSinks.addAll(analysis.unknownSinks);
                    changed |= analysis.isChanged();
                }
            }

            iter++;
        }

        logger.info("Found {} sinks reached from {} sources", sinks.size(), sources.size());
        // For debugging
        /*
        System.out.println(mustNotUsedSinks.size() + " sinks must not be used.");
        for (Taint t : mustNotUsedSinks) {
            System.out.println("-- Sink " + t.toString() + " along:");
        }
        System.out.println("-----------------------------------------------------------");

        System.out.println(unknownSinks.size() + " sinks are unknown to be used.");
        for (Taint t : unknownSinks) {
            System.out.println("-- Sink " + t.toString() + " along:");
        }
        System.out.println("-----------------------------------------------------------");

        System.out.println(mayUseSinks.size() + " sinks may be used.");
        for (Taint t : mayUseSinks) {
            System.out.println("-- Sink " + t.toString() + " along:");
        }
        System.out.println("-----------------------------------------------------------");
         */
    }

    public List<Taint> getSources() { return new ArrayList<>(sources); }

    public List<Taint> getSinks() { return new ArrayList<>(sinks); }

    public Map<SootMethod, Map<Taint, List<Set<Taint>>>> getMethodSummary() {
        return taintMethodSummary;
    }

    public Map<SootMethod, Map<Taint, Taint>> getMethodTaintCache() {
        return methodTaintCache;
    }

    public Map<SootMethod, Map<UniqueStmt, Map<Value, PointsToSet>>> getPointsToMethodSummary() {
        return pointsToMethodSummary;
    }

}
