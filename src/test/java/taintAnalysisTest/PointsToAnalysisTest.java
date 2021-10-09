package taintAnalysisTest;

import org.junit.Assert;
import org.junit.Test;
import soot.SootMethod;
import soot.Value;
import soot.jimple.ReturnStmt;
import taintAnalysis.*;
import taintAnalysis.pointsToAnalysis.AbstractLoc;
import taintAnalysis.pointsToAnalysis.Context;
import taintAnalysis.pointsToAnalysis.LibMethodWrapper;
import taintAnalysis.pointsToAnalysis.pointsToSet.PointsToSet;
import taintAnalysis.sourceSinkManager.ISourceSinkManager;
import taintAnalysis.sourceSinkManager.SourceSinkManager;
import taintAnalysis.taintWrapper.ITaintWrapper;
import taintAnalysis.taintWrapper.TaintWrapper;
import utility.Config;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PointsToAnalysisTest extends TaintAnalysisTest {
    @Test
    public void testInterTaintAnalysis() throws IOException {
        String[] cfg = Config.getCfg("test");
        List<String> srcPaths = Config.getSourcePaths(cfg);
        List<String> classPaths = Config.getClassPaths(cfg);
        ITaintWrapper taintWrapper = TaintWrapper.getDefault();
        LibMethodWrapper libMethodWrapper = LibMethodWrapper.getDefault();
        ISourceSinkManager sourceSinkManager = new SourceSinkManager(Config.getInterface(cfg));
        TaintAnalysisDriver driver = new TaintAnalysisDriver(sourceSinkManager, taintWrapper, libMethodWrapper);
        InterAnalysisTransformer transformer = driver.runInterTaintAnalysis(srcPaths, classPaths, false, true);
        Map<SootMethod, Map<Context, Map<UniqueStmt, Map<Value, PointsToSet>>>> methodSummary
                = transformer.getPointsToMethodSummary();
        int i = 0;
        // test sleep
        /*
        for (SootMethod method : methodSummary.keySet()) {
            if (method.toString().contains("main")) {
                Map<Context, Map<UniqueStmt, Map<Value, Set<AbstractLoc>>>> contextSummary = methodSummary.get(method);
                for (Context context : contextSummary.keySet()) {
                    Map<UniqueStmt, Map<Value, Set<AbstractLoc>>> stmtSummary = contextSummary.get(context);
                    for (UniqueStmt stmt : stmtSummary.keySet()) {
                        if (stmt instanceof ReturnStmt) {
                            Map<Value,Set<AbstractLoc>> valueSummary = stmtSummary.get(stmt);
                            for (Value value : valueSummary.keySet()) {
                            }
                        }
                    }
                }
            }
        }
         */
    }
}
