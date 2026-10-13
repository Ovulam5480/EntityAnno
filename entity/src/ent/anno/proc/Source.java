package ent.anno.proc;

import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.*;
import ent.anno.*;

import java.io.*;
import java.util.*;

import static ent.anno.BaseProcessor.*;
import static javax.lang.model.element.ElementKind.*;
import static javax.lang.model.element.Modifier.*;

public class Source implements Serializable{
    public final List<String> imports = new ArrayList<>();
    public final Map<String, String> varInitializers = new HashMap<>();
    public final Map<String, MethodBlock> methodBlocks = new HashMap<>();

    public Source(BaseProcessor proc, ClassSymbol comp) throws ReprocessedNotRecompiledException{
        var path = proc.trees.getPath(comp);
        if(path == null) throw new ReprocessedNotRecompiledException();

        for(var i : path.getCompilationUnit().getImports()){
            imports.add(i.toString());
        }

        for(var s : comp.getEnclosedElements()){
            if(s.getKind() == FIELD){
                var tree = (JCVariableDecl)proc.trees.getTree(s);
                if(tree == null) continue;

                var init = tree.init;
                if(init != null) varInitializers.put(desc(s), init.toString());
            }else if(s.getKind() == METHOD){
                var m = (MethodSymbol)s;
                if(isAny(m, ABSTRACT, NATIVE)) continue;

                var tree = proc.trees.getTree(m);
                if(tree == null) continue;

                methodBlocks.put(desc(m), new MethodBlock(tree.body));
            }
        }
    }

    static Set<JCIdent> typeIdentifiers(JCTree tree, Set<String> names){
        Set<JCIdent> result = Collections.newSetFromMap(new IdentityHashMap<>());
        new TreeScanner(){
            final Set<String> shadowed = new HashSet<>();
            boolean inType;

            void type(JCTree node){
                boolean previous = inType;
                inType = true;
                scan(node);
                inType = previous;
            }

            @Override public void visitIdent(JCIdent node){
                if(inType && names.contains(node.name.toString()) && !shadowed.contains(node.name.toString())) result.add(node);
            }
            @Override public void visitSelect(JCFieldAccess node){
                if(!inType) super.visitSelect(node);
            }
            @Override public void visitVarDef(JCVariableDecl node){
                type(node.vartype);
                scan(node.init);
            }
            @Override public void visitTypeCast(JCTypeCast node){ type(node.clazz); scan(node.expr); }
            @Override public void visitTypeTest(JCInstanceOf node){ scan(node.expr); type(node.pattern); }
            @Override public void visitNewClass(JCNewClass node){
                scan(node.encl);
                for(var argument : node.typeargs) type(argument);
                type(node.clazz);
                scan(node.args);
                scan(node.def);
            }
            @Override public void visitNewArray(JCNewArray node){ type(node.elemtype); scan(node.dims); scan(node.elems); }
            @Override public void visitApply(JCMethodInvocation node){
                for(var argument : node.typeargs) type(argument);
                scan(node.meth);
                scan(node.args);
            }
            @Override public void visitReference(JCMemberReference node){
                if(node.typeargs != null) for(var argument : node.typeargs) type(argument);
                scan(node.expr);
            }
            @Override public void visitClassDef(JCClassDecl node){
                var previous = new HashSet<>(shadowed);
                for(var parameter : node.typarams) shadowed.add(parameter.name.toString());
                for(var parameter : node.typarams) for(var bound : parameter.bounds) type(bound);
                type(node.extending);
                for(var implemented : node.implementing) type(implemented);
                scan(node.defs);
                shadowed.clear();
                shadowed.addAll(previous);
            }
            @Override public void visitMethodDef(JCMethodDecl node){
                var previous = new HashSet<>(shadowed);
                for(var parameter : node.typarams) shadowed.add(parameter.name.toString());
                for(var parameter : node.typarams) for(var bound : parameter.bounds) type(bound);
                type(node.restype);
                scan(node.params);
                for(var thrown : node.thrown) type(thrown);
                scan(node.body);
                shadowed.clear();
                shadowed.addAll(previous);
            }
        }.scan(tree);
        return result;
    }

    static String expression(JCExpression expression, Map<String, String> replacements){
        var writer = new StringWriter();
        var identifiers = typeIdentifiers(expression, replacements.keySet());
        try{
            new Pretty(writer, true){
                @Override public void visitIdent(JCIdent tree){
                    if(identifiers.contains(tree)){
                        try{ print(replacements.get(tree.name.toString())); }
                        catch(IOException exception){ throw new UncheckedIOException(exception); }
                    }else super.visitIdent(tree);
                }
            }.printExpr(expression);
        }catch(IOException exception){ throw new UncheckedIOException(exception); }
        return writer.toString();
    }

    public static class MethodBlock implements Serializable{
        public final String block;
        public final List<Long> returns = new ArrayList<>();

        public MethodBlock(JCBlock block){
            this(block, Map.of());
        }

        public MethodBlock(JCBlock block, Map<String, String> replacements){
            this(block, replacements, Map.of());
        }

        public MethodBlock(JCBlock block, Map<String, String> replacements, Map<VarSymbol, String> parameters){
            var writer = new StringWriter();
            var identifiers = typeIdentifiers(block, replacements.keySet());
            var parameterNames = new HashMap<String, String>();
            parameters.forEach((parameter, generatedName) -> parameterNames.put(parameter.name.toString(), generatedName));
            new Pretty(writer, true){
                int classLevel = 0;
                @Override
                public void visitIdent(JCIdent tree){
                    if(tree.sym instanceof VarSymbol parameter && parameters.containsKey(parameter)){
                        try{
                            print(parameters.get(parameter));
                        }catch(IOException exception){
                            throw new UncheckedIOException(exception);
                        }
                    }else if(tree.sym == null && classLevel == 0 && parameterNames.containsKey(tree.name.toString())){
                        try{
                            print(parameterNames.get(tree.name.toString()));
                        }catch(IOException exception){
                            throw new UncheckedIOException(exception);
                        }
                    }else if(identifiers.contains(tree)){
                        try{
                            print(replacements.get(tree.name.toString()));
                        }catch(IOException exception){
                            throw new UncheckedIOException(exception);
                        }
                    }else{
                        super.visitIdent(tree);
                    }
                }

                int innerLevel = 0;

                @Override
                public void visitClassDef(JCClassDecl tree){
                    innerLevel++;
                    classLevel++;
                    super.visitClassDef(tree);
                    classLevel--;
                    innerLevel--;
                }

                @Override
                public void visitLambda(JCLambda tree){
                    innerLevel++;
                    super.visitLambda(tree);
                    innerLevel--;
                }

                @Override
                public void visitReturn(JCReturn tree){
                    if(innerLevel > 0){
                        super.visitReturn(tree);
                    }else{
                        long start = writer.getBuffer().length();
                        super.visitReturn(tree);
                        long end = writer.getBuffer().length();

                        returns.add(start | (end << 32));
                    }
                }
            }.visitBlock(block);

            this.block = writer.toString();
        }

        public String substitute(String blockName){
            if(blockName == null) return block.replaceAll("this\\.<(.*)>self\\(\\)", "this")
                .replaceAll("self\\(\\)(?!\\s+instanceof)", "this")
                .replace(" yield ", "")
                .replaceAll("/\\*missing\\*/", "var");

            var builder = new StringBuilder();
            int last = 0;
            for(var ret : returns){
                int start = ret.intValue();
                int end = (int)(ret >>> 32);

                builder.append(block, last, start).append("break ").append(blockName).append(";");
                last = end;
            }

            return builder.append(block, last, block.length()).toString()
                .replaceAll("this\\.<(.*)>self\\(\\)", "this")
                .replaceAll("self\\(\\)(?!\\s+instanceof)", "this")
                .replace(" yield ", "")
                .replaceAll("/\\*missing\\*/", "var");
        }
    }

    public static class ReprocessedNotRecompiledException extends Exception{
    }
}
