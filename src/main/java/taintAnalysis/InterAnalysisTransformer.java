package taintAnalysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.SceneTransformer;
import soot.SootMethod;
import soot.Value;
import taintAnalysis.pointsToAnalysis.AbstractLoc;
import taintAnalysis.pointsToAnalysis.Context;
import taintAnalysis.pointsToAnalysis.LibMethodWrapper;
import taintAnalysis.pointsToAnalysis.PointsToSet;
import taintAnalysis.sourceSinkManager.ISourceSinkManager;
import taintAnalysis.taintWrapper.ITaintWrapper;
import taintAnalysis.utility.PhantomIdentityStmt;
import taintAnalysis.utility.PhantomRetStmt;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class InterAnalysisTransformer extends SceneTransformer {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final InterTaintAnalysis analysis;
    private boolean printResults = true;
    private Map<Taint, List<List<Taint>>> pathsMap = new HashMap<>();

    public InterAnalysisTransformer(ISourceSinkManager sourceSinkManager, ITaintWrapper taintWrapper,
                                    LibMethodWrapper libMethodWrapper, boolean use_points_to) {
        this.analysis = new InterTaintAnalysis(sourceSinkManager, taintWrapper,
                libMethodWrapper, use_points_to);
    }

    public List<Taint> getSources() {
        return analysis.getSources();
    }

    public Map<SootMethod, Map<Taint, List<Set<Taint>>>> getMethodSummary() {
        return analysis.getMethodSummary();
    }

    public Map<SootMethod, Map<Taint, Taint>> getMethodTaintCache() {
        return analysis.getMethodTaintCache();
    }

    public Map<SootMethod, Map<Context, Map<UniqueStmt, Map<Value, PointsToSet>>>> getPointsToMethodSummary() {
        return analysis.getPointsToMethodSummary();
    }

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {
        analysis.doAnalysis();

        Set<Taint> sinks = new HashSet<>();
        ArrayList<Taint> sources = new ArrayList<>(analysis.getSources());
        sources.sort(Comparator.comparing(Taint::toString));

        // // For validation only
        // PathVisitor pv = new PathVisitor();
        // for (Taint source : sources) {
        //     pv.visit(source);
        // }

       int numOfThread = 5;
       logger.info("Reconstructing path using {} threads...", numOfThread);
       ExecutorService es = Executors.newFixedThreadPool(numOfThread);
       List<SourceSinkConnectionVisitor> todo = new ArrayList<>(sources.size());
       for (Taint source : sources) {
           todo.add(new SourceSinkConnectionVisitor(source));
       }
       try {
           es.invokeAll(todo);
       } catch (InterruptedException e) {
           e.printStackTrace();
       }
       for (SourceSinkConnectionVisitor pv : todo) {
           pathsMap.put(pv.getSource(), pv.getPaths());
           sinks.addAll(pv.getSinks());
       }
       es.shutdown();

       logger.info("Number of sinks reached by path reconstruction: {}", sinks.size());

       if (printResults) {
           logger.info("Printing results...");
           for (Taint source : sources) {
               System.out.println("Source: " + source + " reaches:\n");
               List<List<Taint>> paths = pathsMap.get(source);
               for (List<Taint> path : paths) {
                   System.out.println("-- Sink " + path.get(path.size() - 1) + " along:");
                   // get the original indent
                   StringBuilder indent = new StringBuilder(calculateIndent(path));
                   for (Taint t : path) {
                       if (t.getStmt() instanceof PhantomIdentityStmt ||
                               t.getStmt() instanceof PhantomRetStmt)
                           continue;
                        // change the indent if there is method return
                       if (t.isAtReturnSite()) {
                           int end = indent.length();
                           indent.delete(end - 2, end);
                       }
                       System.out.println("    -> " + new String(indent) + t);
                       // change the indent if there is method call
                       if (t.isAtCallSite())
                           indent.append("##");
                   }
                   System.out.println();
               }
               System.out.println();
           }
       }
    }

    public Map<Taint, List<List<Taint>>> getPathsMap() {
        return pathsMap;
    }

    /**
     * Calculate the initial indent of a taint propagation path for a clearer visualization
     * @param path          the taint propagation path
     * @return              the initial indent
     */
    private String calculateIndent(List<Taint> path) {
        // stores taints at call site
        Stack<Taint> callTaintStack = new Stack<>();
        // stores taints at return site
        Stack<Taint> returnTaintStack = new Stack<>();
        // iterate over path, use two stacks to calculate the match of call and return
        for (Taint t : path) {
            if (t.isAtCallSite()) {
                callTaintStack.push(t);
            }
            // when there comes a taint at return site, it can eliminate a stored taint at call site
            else if (t.isAtReturnSite()) {
                if (!callTaintStack.isEmpty()) {
                    callTaintStack.pop();
                }
                else {
                    returnTaintStack.push(t);
                }
            }
        }
        // get the indent finally
        int indentDepth = returnTaintStack.size();
        String indent = "";
        for (int i = 0; i < indentDepth; ++i)
            indent += "##";
        return indent;
    }
}
