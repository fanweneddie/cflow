package taintAnalysisTest;

import assertion.Assert;
import org.junit.Test;
import soot.SootMethod;
import soot.Value;
import taintAnalysis.*;
import taintAnalysis.pointsToAnalysis.Context;
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

import static assertion.Assert.assertEquals;

public class PointsToAnalysisTest extends TaintAnalysisTest {
    @Test
    public void testPointsToAnalysis() throws IOException {
        String[] cfg = Config.getCfg("test");
        List<String> srcPaths = Config.getSourcePaths(cfg);
        List<String> classPaths = Config.getClassPaths(cfg);
        ITaintWrapper taintWrapper = TaintWrapper.getDefault();
        ISourceSinkManager sourceSinkManager = new SourceSinkManager(Config.getInterface(cfg));
        TaintAnalysisDriver driver = new TaintAnalysisDriver(sourceSinkManager, taintWrapper);
        InterAnalysisTransformer transformer = driver.runInterTaintAnalysis(srcPaths, classPaths,
                true, true);
        Map<SootMethod, Map<Taint, List<Set<Taint>>>> methodSummary
                = transformer.getMethodSummary();
        Map<Taint, List<List<Taint>>> pathsMap = transformer.getPathsMap();

        // Test whether return value is tainted(with points-to option)
        for (SootMethod method : methodSummary.keySet()) {
            // 1. Test taints brought by alias
            // If return value is tainted, then it is correct
            if (method.toString().contains("aliasTest1")) {
                Map<Taint, List<Set<Taint>>> currMethodSummary = methodSummary.get(method);
                List<Set<Taint>> taintList = currMethodSummary.get(Taint.getEmptyTaint());
                Set<Taint> retTaintSet = taintList.get(1);
                assertEquals(1, retTaintSet.size());
            }

            if (method.toString().contains("aliasTest2")) {
                Map<Taint, List<Set<Taint>>> currMethodSummary = methodSummary.get(method);
                List<Set<Taint>> taintList = currMethodSummary.get(Taint.getEmptyTaint());
                Set<Taint> retTaintSet = taintList.get(1);
                assertEquals(1, retTaintSet.size());
            }

            // alias field depth = 2 cannot be passed
            // because of our approximation
            if (method.toString().contains("aliasTest3")) {
                Map<Taint, List<Set<Taint>>> currMethodSummary = methodSummary.get(method);
                List<Set<Taint>> taintList = currMethodSummary.get(Taint.getEmptyTaint());
                Set<Taint> retTaintSet = taintList.get(1);
                assertEquals(0, retTaintSet.size());
            }

            // 2. Test taints affected by polymorphism
            if (method.toString().contains("polymorphismTest1")) {
                Map<Taint, List<Set<Taint>>> currMethodSummary = methodSummary.get(method);
                List<Set<Taint>> taintList = currMethodSummary.get(Taint.getEmptyTaint());
                Set<Taint> retTaintSet = taintList.get(1);
                assertEquals(0, retTaintSet.size());
            }

            if (method.toString().contains("polymorphismTest2")) {
                Map<Taint, List<Set<Taint>>> currMethodSummary = methodSummary.get(method);
                List<Set<Taint>> taintList = currMethodSummary.get(Taint.getEmptyTaint());
                Set<Taint> retTaintSet = taintList.get(1);
                assertEquals(1, retTaintSet.size());
            }

            if (method.toString().contains("polymorphismTest3")) {
                Map<Taint, List<Set<Taint>>> currMethodSummary = methodSummary.get(method);
                List<Set<Taint>> taintList = currMethodSummary.get(Taint.getEmptyTaint());
                Set<Taint> retTaintSet = taintList.get(1);
                assertEquals(1, retTaintSet.size());
            }

            if (method.toString().contains("polymorphismTest4")) {
                Map<Taint, List<Set<Taint>>> currMethodSummary = methodSummary.get(method);
                List<Set<Taint>> taintList = currMethodSummary.get(Taint.getEmptyTaint());
                Set<Taint> retTaintSet = taintList.get(1);
                assertEquals(0, retTaintSet.size());
            }

            // 3. Test array accuracy
            if (method.toString().contains("arrayTest1")) {
                Map<Taint, List<Set<Taint>>> currMethodSummary = methodSummary.get(method);
                List<Set<Taint>> taintList = currMethodSummary.get(Taint.getEmptyTaint());
                Set<Taint> retTaintSet = taintList.get(1);
                assertEquals(1, retTaintSet.size());
            }

            if (method.toString().contains("arrayTest2")) {
                Map<Taint, List<Set<Taint>>> currMethodSummary = methodSummary.get(method);
                List<Set<Taint>> taintList = currMethodSummary.get(Taint.getEmptyTaint());
                Set<Taint> retTaintSet = taintList.get(1);
                assertEquals(0, retTaintSet.size());
            }

            // 4. Test sink check
            if (method.getName().equals("SinkCheckTest1")) {
                boolean findSink = false;
                for(List<List<Taint>> taintList : pathsMap.values()) {
                    for (List<Taint> taints : taintList) {
                        int size = taints.size();
                        Taint sinkTaint = taints.get(size - 1);
                        if (sinkTaint.getMethod().toString().contains("SinkCheckTest1")) {
                            findSink = true;
                            break;
                        }
                    }
                }
                Assert.assertTrue(findSink);
            }

            if (method.getName().equals("SinkCheckTest2")) {
                boolean findSink = false;
                for(List<List<Taint>> taintList : pathsMap.values()) {
                    for (List<Taint> taints : taintList) {
                        int size = taints.size();
                        Taint sinkTaint = taints.get(size - 1);
                        if (sinkTaint.getMethod().toString().contains("SinkCheckTest2")) {
                            findSink = true;
                            break;
                        }
                    }
                }
                Assert.assertFalse(findSink);
            }

            if (method.getName().equals("SinkCheckTest3")) {
                boolean findSink = false;
                for(List<List<Taint>> taintList : pathsMap.values()) {
                    for (List<Taint> taints : taintList) {
                        int size = taints.size();
                        Taint sinkTaint = taints.get(size - 1);
                        if (sinkTaint.getMethod().toString().contains("SinkCheckTest3")) {
                            findSink = true;
                            break;
                        }
                    }
                }
                Assert.assertTrue(findSink);
            }

            if (method.getName().equals("SinkCheckTest4")) {
                boolean findSink = false;
                for(List<List<Taint>> taintList : pathsMap.values()) {
                    for (List<Taint> taints : taintList) {
                        int size = taints.size();
                        Taint sinkTaint = taints.get(size - 1);
                        if (sinkTaint.getMethod().toString().contains("SinkCheckTest4")) {
                            findSink = true;
                            break;
                        }
                    }
                }
                Assert.assertTrue(findSink);
            }

            if (method.getName().equals("SinkCheckTest5")) {
                boolean findSink = false;
                for(List<List<Taint>> taintList : pathsMap.values()) {
                    for (List<Taint> taints : taintList) {
                        int size = taints.size();
                        Taint sinkTaint = taints.get(size - 1);
                        if (sinkTaint.getMethod().toString().contains("SinkCheckTest5")) {
                            findSink = true;
                            break;
                        }
                    }
                }
                Assert.assertFalse(findSink);
            }
        }
    }
}
