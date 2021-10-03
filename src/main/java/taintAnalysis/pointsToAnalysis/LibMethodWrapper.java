package taintAnalysis.pointsToAnalysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.SootMethod;
import taintAnalysis.taintWrapper.TaintWrapper;

import java.io.*;
import java.util.HashSet;
import java.util.Set;

public class LibMethodWrapper {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Set<String> libMethodSet;

    // get the lib methods from a file
    public LibMethodWrapper(String file) throws IOException {
        Reader reader = new FileReader(new File(file).getAbsoluteFile());
        BufferedReader bufReader = new BufferedReader(reader);
        libMethodSet = new HashSet<>();
        try {
            String line = bufReader.readLine();
            while (line != null) {
                libMethodSet.add(line);
                line = bufReader.readLine();
            }
            logger.info("Loaded library method list with {} methods", libMethodSet.size());
        }
        finally {
            bufReader.close();
        }
    }

    public static LibMethodWrapper getDefault() throws IOException {
        return new LibMethodWrapper("LibraryMethodList.txt");
    }

    public boolean check(SootMethod method) {
        String sig = method.getSignature();
        if (libMethodSet.contains(sig)) {
            return true;
        }
        else {
            return false;
        }
    }
}
