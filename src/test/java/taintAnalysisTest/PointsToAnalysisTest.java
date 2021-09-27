package taintAnalysisTest;

import org.junit.Assert;
import org.junit.Test;
import soot.SootMethod;
import soot.Value;
import taintAnalysis.*;
import taintAnalysis.pointsToAnalysis.AbstractLoc;
import taintAnalysis.pointsToAnalysis.Context;
import taintAnalysis.sourceSinkManager.ISourceSinkManager;
import taintAnalysis.sourceSinkManager.SourceSinkManager;
import utility.Config;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class PointsToAnalysisTest extends TaintAnalysisTest {
    @Test
    public void testInterTaintAnalysis() {
        String[] cfg = Config.getCfg("test");
        List<String> srcPaths = Config.getSourcePaths(cfg);
        List<String> classPaths = Config.getClassPaths(cfg);
        ISourceSinkManager sourceSinkManager = new SourceSinkManager(Config.getInterface(cfg));
        TaintAnalysisDriver driver = new TaintAnalysisDriver(sourceSinkManager);
        InterAnalysisTransformer transformer = driver.runInterTaintAnalysis(srcPaths, classPaths, false, true);
        Map<SootMethod, Map<Context, Map<UniqueStmt, Map<Value,Set<AbstractLoc>>>>> methodSummary
                = transformer.getPointsToMethodSummary();
    }
}
