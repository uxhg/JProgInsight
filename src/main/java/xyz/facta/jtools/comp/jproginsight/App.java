package xyz.facta.jtools.comp.jproginsight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.cli.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import spoon.Launcher;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.Factory;
import spoon.reflect.visitor.filter.AbstractFilter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class App {
    private static final Logger logger = LogManager.getLogger(App.class);
    private static final List<CGEdge> callGraphEdges = new ArrayList<>();

    private static final Map<String, String> methodSignatureToDefinitionMap = new HashMap<>();

    public static void main(String[] args) {
        Options options = new Options();

        Option input = new Option("i", "input", true, "path of input source files");
        input.setRequired(true);
        options.addOption(input);

        Option output = new Option("o", "output", true, "output directory path");
        output.setRequired(true);
        options.addOption(output);
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("JProgInsight", options);
            System.exit(1);
            return;
        }

        String inputResourcePath = cmd.getOptionValue("input");
        String baseOutputDirectoryPath = cmd.getOptionValue("output");


        Factory factory = launchSpoon(inputResourcePath, baseOutputDirectoryPath);
        visitClass(factory);
        writeCallGraphToFile(baseOutputDirectoryPath);
        writeMethodMappingToJsonFile(baseOutputDirectoryPath);
    }

    private static Factory launchSpoon(String inputPath, String outputPath) {
        Launcher spoon = new Launcher();
        spoon.addInputResource(inputPath);
        spoon.buildModel();
        return spoon.getFactory();
    }

    private static void visitClass(Factory factory) {
        for (CtType<?> type : factory.Class().getAll()) {
            if (type.isInterface() || type.isAnnotationType()) continue;
            CtClass clazz = (CtClass) type;
            visitMethod(clazz);
        }
    }

    private static void visitMethod(CtClass clazz) {
        Set<CtMethod> methodSet = clazz.getAllMethods();
        for (CtMethod method : methodSet) {
            String methodSourceCode = method.toString();
            String caller_class = clazz.getQualifiedName();
            String caller_sig = method.getSignature();
            methodSignatureToDefinitionMap.put(caller_class + "." + caller_sig, methodSourceCode);
            final List<CtInvocation<?>> elements = method.getElements(new AbstractFilter<CtInvocation<?>>() {
                @Override
                public boolean matches(CtInvocation<?> element) {
                    return super.matches(element);
                }
            });
            for (CtInvocation element : elements) {
                String callee_class = "N_A";
                try {
                    callee_class = element.getExecutable().getDeclaringType().toString();
                } catch (NullPointerException e) {
                    logger.debug("Cannot get callee declaring type");
                }
                String callee_sig = element.getExecutable().getSignature();
                callGraphEdges.add(new CGEdge(caller_class, caller_sig, callee_class, callee_sig));
            }
        }
    }

    private static void writeCallGraphToFile(String outputPath) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath + "/call_graph.tsv"))) {
            for (CGEdge edge : callGraphEdges) {
                writer.write(edge.toString() + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private static void writeMethodMappingToJsonFile(String outputPath) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        try {
            objectMapper.writeValue(new File(outputPath + "/method_mapping.json"), methodSignatureToDefinitionMap);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
