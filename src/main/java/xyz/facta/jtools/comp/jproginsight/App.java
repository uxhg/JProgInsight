package xyz.facta.jtools.comp.jproginsight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.cli.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import spoon.Launcher;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
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
    private static int countCalleesWithoutType = 0; // counters for: how many callees cannot get their class names
    private static int countCalleesWithoutDefinition = 0; // counters for: how many callees cannot get their definitions

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

        processInput(inputResourcePath, baseOutputDirectoryPath);
    }

    private static void processInput(String inputResourcePath, String baseOutputDirectoryPath) {
        List<CGEdge> callGraphEdges = new ArrayList<>();
        Map<String, String> methodSignatureToDefinitionMap = new HashMap<>();
        Factory factory = launchSpoon(inputResourcePath);
        visitClasses(factory, callGraphEdges, methodSignatureToDefinitionMap);
        writeCallGraphToFile(baseOutputDirectoryPath, callGraphEdges);
        writeMethodMappingToJsonFile(baseOutputDirectoryPath, methodSignatureToDefinitionMap);
        Map<String, List<String>> callerToCalleeMap = createCallerToCalleesMapping(callGraphEdges);
        generateCGwithMethodBody(callerToCalleeMap, methodSignatureToDefinitionMap, baseOutputDirectoryPath);
        reportUnresolvedCounts();
    }

    private static Factory launchSpoon(String inputPath) {
        Launcher spoon = new Launcher();
        spoon.addInputResource(inputPath);
        spoon.buildModel();
        return spoon.getFactory();
    }

    private static void visitClasses(Factory factory, List<CGEdge> callGraphEdges, Map<String, String> methodSignatureToDefinitionMap) {
        for (CtType<?> type : factory.Class().getAll()) {
            processType(callGraphEdges, methodSignatureToDefinitionMap, type);
        }
    }

    private static void processType(List<CGEdge> callGraphEdges, Map<String, String> methodSignatureToDefinitionMap, CtType<?> type) {
        if (type.isInterface() || type.isAnnotationType()) return;

        if (type instanceof CtClass) {
            logger.debug("Inspect type: {}", type.getQualifiedName());
            CtClass clazz = (CtClass) type;
            visitMethod(clazz, callGraphEdges, methodSignatureToDefinitionMap);
        }
        // Recursively process nested types
        for (CtType<?> nestedType : type.getNestedTypes()) {
            //logger.debug("Found nested type: {}", nestedType.getQualifiedName());
            processType(callGraphEdges, methodSignatureToDefinitionMap, nestedType);
        }
    }

    private static void visitMethod(CtClass clazz, List<CGEdge> callGraphEdges, Map<String, String> methodSignatureToDefinitionMap) {
        Set<CtMethod> methodSet = clazz.getAllMethods();
        for (CtMethod method : methodSet) {
            String methodSourceCode = method.toString();
            String caller_class = clazz.getQualifiedName();
            String caller_sig = method.getSignature();
            methodSignatureToDefinitionMap.put(caller_class + "." + caller_sig, methodSourceCode);
            final List<CtInvocation<?>> elements = method.getElements(new AbstractFilter<>() {
                @Override
                public boolean matches(CtInvocation<?> element) {
                    return super.matches(element);
                }
            });
            for (CtInvocation element : elements) {
                String callee_class = getCalleeClassNameComplex(element);
                //if (!Objects.equals(element.getExecutable().getDeclaration().toString(), element.getTarget().getType().getQualifiedName())) {
                //    logger.error("callee_class: {}, target_type: {}", element.getExecutable().getDeclaration().toString(), element.getTarget().getType().getQualifiedName());
                //}
                String callee_sig = element.getExecutable().getSignature();
                callGraphEdges.add(new CGEdge(caller_class, caller_sig, callee_class, callee_sig));
            }
        }
    }

    private static String getCalleeClassName(CtInvocation element) {
        String callee_class = "N_A";
        try {
            callee_class = element.getExecutable().getDeclaringType().getQualifiedName();
        } catch (NullPointerException e) {
            logger.info("Cannot get callee declaring type for {}", element.toString());
            countCalleesWithoutType++;
        }
        return callee_class;
    }

    private static String getCalleeClassNameComplex(CtInvocation element) {
        String callee_class = "N_A";
        CtExpression<?> target = element.getTarget();
        logger.error("element: {}, target: {}, type: {}", element.toString(), target, target.getType());
        if (target instanceof CtFieldRead) {// If encountering static field like CSVFormat.DEFAULT.methodcall()
            CtFieldRead<?> fieldRead = (CtFieldRead<?>) target;
            if (fieldRead.getVariable().isStatic()) {
                // Use the type of the static field
                try {
                    callee_class = fieldRead.getType().getQualifiedName();
                    logger.error("callee class name is: {}", callee_class);
                } catch (NullPointerException e) {
                    logger.info("Cannot get callee type for {}", element.toString());
                    countCalleesWithoutType++;
                }
            }
        } else {
            try {
                logger.error("element: {}, exec: {}, type: {}", element.toString(), element.getExecutable(), element.getExecutable().getDeclaringType());
                callee_class = element.getExecutable().getDeclaringType().getQualifiedName();
            } catch (NullPointerException e) {
                logger.info("Cannot get callee declaring type for {}", element.toString());
                countCalleesWithoutType++;
            }
        }
        return callee_class;
    }

    private static void writeCallGraphToFile(String outputPath, List<CGEdge> callGraphEdges) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath + "/call_graph.tsv"))) {
            for (CGEdge edge : callGraphEdges) {
                writer.write(edge.toString() + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void writeMethodMappingToJsonFile(String outputPath, Map<String, String> methodSignatureToDefinitionMap) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        try {
            objectMapper.writeValue(new File(outputPath + "/method_mapping.json"), methodSignatureToDefinitionMap);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Map<String, List<String>> createCallerToCalleesMapping(List<CGEdge> callGraphEdges) {
        Map<String, List<String>> callerToCallees = new HashMap<>();
        for (CGEdge edge : callGraphEdges) {
            String caller = edge.originClass + "." + edge.originMethodSig;
            String callee = edge.destMethodSig;
            if (!Objects.equals(edge.destClass, "N.A")) {
                callee = edge.destClass + "." + edge.destMethodSig;
            }
            callerToCallees.computeIfAbsent(caller, k -> new ArrayList<>()).add(callee);
        }
        return callerToCallees;
    }

    private static void generateCGwithMethodBody(Map<String, List<String>> callerToCallees, Map<String, String> methodSignatureToDefinitionMap, String outputPath) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode jsonArray = mapper.createArrayNode();

        for (Map.Entry<String, List<String>> entry : callerToCallees.entrySet()) {
            String callerSig = entry.getKey();
            String callerDef = methodSignatureToDefinitionMap.get(callerSig);

            if (callerDef == null) {
                logger.warn("Definition not found for caller:" + callerSig);
                continue;
            }

            ObjectNode callerObject = mapper.createObjectNode();
            callerObject.put("caller_name", callerSig);
            callerObject.put("caller_def", callerDef);

            ArrayNode calleeArray = mapper.createArrayNode();
            for (String calleeSig : entry.getValue()) {
                String calleeDef = methodSignatureToDefinitionMap.get(calleeSig);
                if (calleeDef == null) {
                    // many callees have no definition because they are from external libraries
                    if (calleeSig.startsWith("N_A.")) {
                        logger.debug("Definition not found for callee because it class name is unknown, it should be from external libraries:" + calleeSig);
                    } else if (calleeSig.startsWith("java.") || calleeSig.startsWith("javax.")) {
                        logger.debug("Definition not found for callee because it is from java... or javax... libraries:" + calleeSig);
                    } else {
                        logger.info("Definition not found for callee:" + calleeSig);
                    }
                    countCalleesWithoutDefinition++;
                    continue;
                }

                ObjectNode calleeObject = mapper.createObjectNode();
                calleeObject.put("callee_name", calleeSig);
                calleeObject.put("callee_def", calleeDef);
                calleeArray.add(calleeObject);
            }
            if (!calleeArray.isEmpty()) {
                // only add this entry if there is at least one callee
                callerObject.set("callee_list", calleeArray);
                jsonArray.add(callerObject);
            }
        }

        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputPath + "/call_graph_with_defs.json"), jsonArray);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void reportUnresolvedCounts() {
        System.out.println("Number of callees without type (not unique): " + countCalleesWithoutType);
        System.out.println("Number of callees without definition (not unique): " + countCalleesWithoutDefinition);
    }

}
