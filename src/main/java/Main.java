import acai.Acai;
import acai.configInterface.ConfigInterface;
import acai.utility.AcaiConfig;
import checking.CheckPass;
import checking.DataTypeChk;
import org.apache.commons.cli.*;
import soot.jimple.infoflow.results.InfoflowResults;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.*;

public class Main {

    public static void main(String[] args) throws IOException {

        CommandLineParser parser = new DefaultParser();
        Options options = new Options();
        Option optionA = Option.builder("o")
                .required(false)
                .desc("This parameter specifies the exported file path. If not specified, output will be directed to stdout.")
                .longOpt("output")
                .hasArg()
                .build();

        Option optionC = Option.builder("a")
                .required(true)
                .desc("Support applications are: hdfs, mapreduce, yarn, hadoop_common, hadoop_tools, hbase, alluxio, zookeeper, spark")
                .longOpt("app")
                .hasArg()
                .build();

        Option optionB = Option.builder("x")
                .required(true)
                .desc("Configuration parameter directory path.")
                .longOpt("xml")
                .hasArg()
                .build();

        options.addOption(optionA);
        options.addOption(optionB);
        options.addOption(optionC);

        try {
            CommandLine commandLine = parser.parse(options, args);

            /* getting optional parameters */
            if (commandLine.hasOption('o')){
                /* getting option o */
                String filePath =  commandLine.getOptionValue('o');
                PrintStream fileOut = new PrintStream(new FileOutputStream(filePath, true));
                System.setOut(fileOut);
            }

            /* getting required parameters */
            /* getting option x */
            String xmlPath = commandLine.getOptionValue('x');
            File folder = new File(xmlPath);
            File[] xmlFiles = folder.listFiles();
            String[] fileNames = new String[xmlFiles.length];
            for (int i = 0; i < xmlFiles.length; i++)
                fileNames[i] = xmlFiles[i].getAbsolutePath();

            /* getting option a */
            String apps = commandLine.getOptionValue('a');
            String[] result = apps.split(",");
            String[][] considered = new String[result.length][];
            for (int i = 0; i < considered.length; i++) {
                try {
                    considered[i] = AcaiConfig.getCfg(result[i]);
                } catch (IllegalArgumentException e) {
                    throw new ParseException(result[i] + " not found in supported application");
                }
            }

            run(considered);

        } catch (ParseException ex) {
            System.out.println(ex.getMessage());
            new HelpFormatter().printHelp("ccc",options);
        }

    }

    private static void run(String[][] considered) {
        List<String> appPaths = new LinkedList<>();
        List<String> srcPaths = new LinkedList<>();
        List<String> classPaths = new LinkedList<>();
        ConfigInterface configInterface = null;

        for (String[] cfg : considered) {
            appPaths.addAll(AcaiConfig.getAppPaths(cfg));
            srcPaths.addAll(AcaiConfig.getSourcePaths(cfg));
            classPaths.addAll(AcaiConfig.getClassPaths(cfg));
            configInterface = AcaiConfig.getInterface(cfg);
        }

        // Run infoflow analysis
        Acai acai = new Acai(appPaths, srcPaths, classPaths, configInterface);
        acai.computeInfoflow();
        InfoflowResults results = acai.getResults();

        // Run checking
        runChecking(configInterface, results);
    }

    private static void runChecking(ConfigInterface configInterface, InfoflowResults results) {
        CheckPass chkPass = new DataTypeChk();
        chkPass.runChecking(configInterface, results);
    }

}
