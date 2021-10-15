package taintAnalysis.pointsToAnalysis;

import soot.Value;
import taintAnalysis.UniqueStmt;
import taintAnalysis.pointsToAnalysis.pointsToSet.PointsToSet;

import java.util.*;

/**
 * This class stores the information of a method for points-to analysis
 */
public class MethodInfo {
    // The program states of points-to analysis,
    // which is method, statement, variable value -> points-to set
    private final Map<UniqueStmt, Map<Value, PointsToSet>> programStates;
    // It gets the reference of each points-to set,
    // which hints the variable that points to the points-to set,
    // given the specialVarList in finalSummary
    private final Map<PointsToSet, Reference> refMap;
    // The summary of this method for points-to analysis
    private final Summary finalSummary;
    // The final statement of the method
    private UniqueStmt finalStmt;
    // It shows whether this method has been analyzed
    private boolean isAnalyzed;

    /** Naive constructor */
    public MethodInfo(Summary summary) {
        this.programStates = new HashMap<>();
        this.refMap = new HashMap<>();
        this.finalSummary = summary;
        this.finalStmt = null;
        this.isAnalyzed = false;
    }

    public Map<UniqueStmt, Map<Value, PointsToSet>> getProgramStates() {
        return programStates;
    }

    public Map<PointsToSet, Reference> getRefMap() {
        return refMap;
    }

    public Summary getFinalSummary() {
        return finalSummary;
    }

    public void setFinalStmt(UniqueStmt finalStmt) {
        this.finalStmt = finalStmt;
    }

    public UniqueStmt getFinalStmt() {
        return finalStmt;
    }

    public boolean isAnalyzed() {
        return isAnalyzed;
    }

    public void setAsAnalyzed() {
        isAnalyzed = true;
    }

}
