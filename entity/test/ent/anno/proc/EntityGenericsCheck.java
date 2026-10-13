package ent.anno.proc;

import com.squareup.javapoet.*;
import com.sun.source.util.JavacTask;
import ent.anno.Annotations.EntityDef;

import javax.lang.model.element.*;
import javax.tools.*;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

public final class EntityGenericsCheck{
    static JavaFileObject source(String name, String text){
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE){
            @Override public CharSequence getCharContent(boolean ignore){ return text; }
        };
    }

    static EntityDef definition(EntityDef.GenericMode mode, String type){
        return new EntityDef(){
            public Class<?>[] value(){ return new Class<?>[0]; }
            public boolean serialize(){ return false; }
            public boolean genIO(){ return false; }
            public boolean pooled(){ return false; }
            public GenericMode genericMode(){ return mode; }
            public String genericName(){ return "E"; }
            public String genericType(){ return type; }
            public Class<? extends Annotation> annotationType(){ return EntityDef.class; }
        };
    }

    public static void main(String[] args) throws Exception{
        var compiler = ToolProvider.getSystemJavaCompiler();
        Path output = Files.createTempDirectory("entity-generics-check");
        try(var files = compiler.getStandardFileManager(null, null, null)){
            var fixture = source("fixture.Fixture", """
                package fixture;
                interface Left<T extends Number> { T get(); }
                interface Right<U extends Comparable<U>> {}
                abstract class First<T extends Number> implements Left<T> { T value; }
                abstract class Second<U extends Comparable<U>> implements Right<U> {}
                interface Self<T extends Self<T>> {}
                abstract class Recursive<S extends Self<S>> implements Self<S> {}
                """);
            var task = (JavacTask)compiler.getTask(null, files, null, List.of("-proc:none"), null, List.of(fixture, source("LLL.world.modules.BaseConsumeModule", """
                package LLL.world.modules;
                public class BaseConsumeModule<T> { public class inner<U> {} }
                """)));
            task.parse();
            task.analyze();
            var elements = task.getElements();
            var first = elements.getTypeElement("fixture.First");
            var second = elements.getTypeElement("fixture.Second");
            var left = elements.getTypeElement("fixture.Left");
            var right = elements.getTypeElement("fixture.Right");
            for(var mode : List.of(EntityDef.GenericMode.PRESERVE, EntityDef.GenericMode.BIND)){
                var name = ClassName.get("fixture", "Generated" + mode);
                var mapping = new EntityGenerics(definition(mode, mode == EntityDef.GenericMode.BIND ? "java.lang.Integer" : ""), name, List.of(first, second), task.getElements());
                var field = first.getEnclosedElements().stream().filter(e -> e.getKind() == ElementKind.FIELD).findFirst().orElseThrow();
                var builder = TypeSpec.classBuilder(name.simpleName()).addModifiers(Modifier.PUBLIC)
                    .addTypeVariables(mapping.declarations)
                    .addSuperinterface(mapping.applied(ClassName.get(left), left))
                    .addSuperinterface(mapping.applied(ClassName.get(right), right))
                    .addField(mapping.replace(TypeName.get(field.asType())), "value")
                    .addMethod(MethodSpec.methodBuilder("get").addModifiers(Modifier.PUBLIC).returns(mapping.argument).addStatement("return value").build())
                    .addMethod(MethodSpec.methodBuilder("create").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .addTypeVariables(mapping.declarations).returns(mapping.entityType(name))
                        .addStatement("return new $T()", mapping.entityType(name)).build());
                checkCompile(compiler, files, output, fixture, name, builder, true);
            }
            var recursive = elements.getTypeElement("fixture.Recursive");
            var self = elements.getTypeElement("fixture.Self");
            var name = ClassName.get("fixture", "GeneratedSelf");
            var mapping = new EntityGenerics(definition(EntityDef.GenericMode.SELF, ""), name, List.of(recursive), elements);
            checkCompile(compiler, files, output, fixture, name, TypeSpec.classBuilder(name.simpleName())
                .addSuperinterface(mapping.applied(ClassName.get(self), self)), true);
            var bad = new EntityGenerics(definition(EntityDef.GenericMode.BIND, "java.lang.String"), name, List.of(first), elements);
            checkCompile(compiler, files, output, fixture, name, TypeSpec.classBuilder(name.simpleName())
                .addModifiers(Modifier.ABSTRACT).addSuperinterface(bad.applied(ClassName.get(left), left)), false);
            var nested = new EntityGenerics(definition(EntityDef.GenericMode.BIND, "java.util.Map<java.lang.String, java.util.List<? extends java.lang.Number[]>>"), name, List.of(first), elements);
            if(!nested.argument.toString().contains("? extends java.lang.Number[]")) throw new AssertionError(nested.argument);
            try{
                new EntityGenerics(definition(EntityDef.GenericMode.BIND, "java.util.List<"), name, List.of(first), elements);
                throw new AssertionError("Malformed type accepted");
            }catch(IllegalArgumentException expected){}
            var enclosing = ParameterizedTypeName.get(ClassName.get("fixture", "Outer"), TypeVariableName.get("S"))
                .nestedClass("Inner", List.of(TypeVariableName.get("S")));
            if(!mapping.replace(enclosing).toString().equals("fixture.Outer<fixture.GeneratedSelf>.Inner<fixture.GeneratedSelf>"))
                throw new AssertionError(mapping.replace(enclosing));
            var module = ClassName.get(elements.getTypeElement("LLL.world.modules.BaseConsumeModule"));
            var moduleType = ParameterizedTypeName.get(module, TypeVariableName.get("S"));
            if(!mapping.replace(moduleType).toString().equals("LLL.world.modules.BaseConsumeModule<fixture.GeneratedSelf>"))
                throw new AssertionError(mapping.replace(moduleType));
            var innerType = moduleType.nestedClass("inner", List.of(TypeVariableName.get("S")));
            if(!mapping.replace(innerType).toString().equals("LLL.world.modules.BaseConsumeModule<fixture.GeneratedSelf>.inner<fixture.GeneratedSelf>"))
                throw new AssertionError(mapping.replace(innerType));
            for(var binding : List.of("LLL.world.modules.BaseConsumeModule<java.lang.Integer>",
                "LLL.world.modules.BaseConsumeModule<java.lang.Integer>.inner<java.lang.String>")){
                var bound = new EntityGenerics(definition(EntityDef.GenericMode.BIND, binding), name, List.of(first), elements);
                if(!bound.argument.toString().equals(binding)) throw new AssertionError(bound.argument);
            }
            var syntaxTask = (JavacTask)compiler.getTask(null, files, null, List.of("-proc:none"), null, List.of(source("fixture.Syntax", """
                package fixture;
                class Syntax<T> {
                    java.util.List<T> list = new java.util.ArrayList<T>();
                    void run() {
                        T value = null;
                        String T = "T";
                        Object cast = (T)value;
                        java.util.List<T> values = new java.util.ArrayList<T>();
                        class Local<T> { T local; }
                        Runnable action = () -> { return; };
                        return;
                    }
                }
                """)));
            var unit = syntaxTask.parse().iterator().next();
            var syntax = (com.sun.tools.javac.tree.JCTree.JCClassDecl)unit.getTypeDecls().get(0);
            var initializer = (com.sun.tools.javac.tree.JCTree.JCVariableDecl)syntax.defs.get(0);
            if(!Source.expression(initializer.init, Map.of("T", "java.lang.Integer")).contains("<java.lang.Integer>"))
                throw new AssertionError("Initializer type not replaced");
            var method = (com.sun.tools.javac.tree.JCTree.JCMethodDecl)syntax.defs.get(1);
            var block = new Source.MethodBlock(method.body, Map.of("T", "java.lang.Integer"));
            if(!block.block.contains("java.lang.Integer value") || !block.block.contains("String T = \"T\"")
                || !block.block.contains("T local") || !block.block.contains("(java.lang.Integer)value")
                || block.returns.size() != 1 || !block.substitute("done").contains("break done;"))
                throw new AssertionError(block.block);
            System.out.println("Generic generated-source checks passed");
        }finally{
            try(var paths = Files.walk(output)){
                for(var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    static void checkCompile(JavaCompiler compiler, StandardJavaFileManager files, Path output,
                             JavaFileObject fixture, ClassName name, TypeSpec.Builder builder, boolean expected){
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var generated = JavaFile.builder("fixture", builder.build()).build().toString();
        boolean success = compiler.getTask(null, files, diagnostics, List.of("-proc:none", "-d", output.toString()), null,
            List.of(fixture, source(name.canonicalName(), generated))).call();
        if(success != expected) throw new AssertionError(generated + "\n" + diagnostics.getDiagnostics());
    }
}
