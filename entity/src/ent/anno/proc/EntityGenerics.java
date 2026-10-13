package ent.anno.proc;

import com.squareup.javapoet.*;
import ent.anno.Annotations.EntityDef;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import java.util.*;

/** The shared type argument of an explicitly generic entity definition. */
final class EntityGenerics{
    final EntityDef.GenericMode mode;
    final TypeName argument;
    final Map<String, TypeName> variables = new LinkedHashMap<>();
    final List<TypeVariableName> declarations = new ArrayList<>();

    final Elements elements;

    EntityGenerics(EntityDef annotation, ClassName entity, Iterable<? extends TypeElement> components, Elements elements){
        this.elements = elements;
        mode = annotation.genericMode();
        if(mode == EntityDef.GenericMode.NONE){
            argument = null;
            if(!annotation.genericType().isBlank()) throw new IllegalArgumentException("genericType requires BIND mode");
            return;
        }
        if(!SourceVersion.isIdentifier(annotation.genericName()) || SourceVersion.isKeyword(annotation.genericName()))
            throw new IllegalArgumentException("Invalid genericName: " + annotation.genericName());
        if(mode != EntityDef.GenericMode.BIND && !annotation.genericType().isBlank())
            throw new IllegalArgumentException("genericType is only supported in BIND mode");
        argument = switch(mode){
            case PRESERVE -> TypeVariableName.get(annotation.genericName());
            case SELF -> entity;
            case BIND -> new TypeParser(annotation.genericType()).parse();
            default -> throw new AssertionError(mode);
        };
        var parameters = new ArrayList<TypeParameterElement>();
        for(var component : components){
            if(component.getTypeParameters().size() > 1)
                throw new IllegalArgumentException("Shared generic mode requires at most one type parameter per component: " + component);
            for(var parameter : component.getTypeParameters()){
                variables.put(parameter.getSimpleName().toString(), argument);
                parameters.add(parameter);
            }
        }
        if(parameters.isEmpty()) throw new IllegalArgumentException("Generic mode requires a generic component");
        if(mode == EntityDef.GenericMode.PRESERVE){
            var bounds = new LinkedHashMap<String, TypeName>();
            for(var parameter : parameters){
                for(var bound : parameter.getBounds()){
                    var converted = replace(TypeName.get(bound));
                    if(!converted.equals(TypeName.OBJECT)) bounds.putIfAbsent(converted.toString(), converted);
                }
            }
            // Java requires the class bound to precede all interface bounds.
            var ordered = new ArrayList<TypeName>();
            for(var parameter : parameters){
                for(var bound : parameter.getBounds()){
                    if(bound instanceof javax.lang.model.type.DeclaredType declared
                        && declared.asElement().getKind().isClass()){
                        var converted = replace(TypeName.get(bound));
                        if(!converted.equals(TypeName.OBJECT) && !ordered.contains(converted)) ordered.add(converted);
                    }
                }
            }
            if(ordered.size() > 1) throw new IllegalArgumentException("Components require incompatible class bounds: " + ordered);
            for(var bound : bounds.values()) if(!ordered.contains(bound)) ordered.add(bound);
            declarations.add(TypeVariableName.get(annotation.genericName(), ordered.toArray(TypeName[]::new)));
        }
    }

    boolean enabled(){ return argument != null; }

    TypeName applied(ClassName raw, TypeElement component){
        return enabled() && !component.getTypeParameters().isEmpty()
            ? ParameterizedTypeName.get(raw, argument) : raw;
    }

    TypeName entityType(ClassName raw){
        return mode == EntityDef.GenericMode.PRESERVE ? ParameterizedTypeName.get(raw, argument) : raw;
    }

    TypeName replace(TypeName type){ return replace(type, Set.of()); }

    TypeName replace(TypeName type, Set<String> shadowed){
        if(!enabled()) return type;
        if(type instanceof TypeVariableName variable){
            if(!shadowed.contains(variable.name) && variables.containsKey(variable.name)) return argument;
            return variable;
        }
        if(type instanceof ArrayTypeName array) return ArrayTypeName.of(replace(array.componentType, shadowed));
        if(type instanceof ParameterizedTypeName parameterized){
            var substitutions = new HashMap<String, TypeName>(variables);
            for(var name : shadowed) substitutions.put(name, TypeVariableName.get(name));
            if(parameterized.rawType.enclosingClassName() == null){
                return ParameterizedTypeName.get(parameterized.rawType, parameterized.typeArguments.stream()
                    .map(value -> replace(value, shadowed)).toArray(TypeName[]::new));
            }
            var parser = new TypeParser(parameterized.toString(), substitutions);
            parser.register(parameterized);
            return parser.parse();
        }
        if(type instanceof WildcardTypeName wildcard){
            return wildcard.lowerBounds.isEmpty()
                ? WildcardTypeName.subtypeOf(replace(wildcard.upperBounds.get(0), shadowed))
                : WildcardTypeName.supertypeOf(replace(wildcard.lowerBounds.get(0), shadowed));
        }
        return type;
    }

    /** Parses qualified Java reference types; javac checks names and bounds in generated code. */
    private final class TypeParser{
        final String text;
        int position;
        final Map<String, TypeName> substitutions;
        final Map<String, ClassName> knownClasses = new HashMap<>();
        void register(TypeName type){
            if(type instanceof ClassName clazz){
                for(; clazz != null; clazz = clazz.enclosingClassName()) knownClasses.put(clazz.canonicalName(), clazz);
            }else if(type instanceof ParameterizedTypeName parameterized){
                register(parameterized.rawType);
                parameterized.typeArguments.forEach(this::register);
            }else if(type instanceof ArrayTypeName array) register(array.componentType);
            else if(type instanceof WildcardTypeName wildcard){
                wildcard.upperBounds.forEach(this::register);
                wildcard.lowerBounds.forEach(this::register);
            }
        }
        ClassName resolve(String name){
            if(knownClasses.containsKey(name)) return knownClasses.get(name);
            var element = elements.getTypeElement(name);
            if(element == null) throw new IllegalArgumentException("Cannot resolve generic type: " + name);
            return ClassName.get(element);
        }
        TypeParser(String text){ this(text, null); }
        TypeParser(String text, Map<String, TypeName> substitutions){ this.text = text; this.substitutions = substitutions; }
        TypeName parse(){
            var type = type(false);
            space();
            if(position != text.length() || type instanceof WildcardTypeName)
                throw invalid();
            return type;
        }
        TypeName type(boolean wildcard){
            space();
            if(take('?')){
                if(!wildcard) throw invalid();
                if(word("extends")) return WildcardTypeName.subtypeOf(type(false));
                if(word("super")) return WildcardTypeName.supertypeOf(type(false));
                return WildcardTypeName.subtypeOf(Object.class);
            }
            StringBuilder name = new StringBuilder(identifier());
            while(take('.')) name.append('.').append(identifier());
            if(name.indexOf(".") < 0 && substitutions == null) throw new IllegalArgumentException("genericType must use fully qualified class names: " + name);
            TypeName result = name.indexOf(".") < 0
                ? substitutions.getOrDefault(name.toString(), TypeVariableName.get(name.toString()))
                : resolve(name.toString());
            result = arguments(result);
            while(take('.')){
                String nested = identifier();
                var args = readArguments();
                if(result instanceof ParameterizedTypeName parameterized)
                    result = parameterized.nestedClass(nested, args);
                else{
                    var nestedType = ((ClassName)result).nestedClass(nested);
                    result = args.isEmpty() ? nestedType : ParameterizedTypeName.get(nestedType, args.toArray(TypeName[]::new));
                }
            }
            while(take('[')){
                if(!take(']')) throw invalid();
                result = ArrayTypeName.of(result);
            }
            return result;
        }
        TypeName arguments(TypeName raw){
            var args = readArguments();
            return args.isEmpty() ? raw : ParameterizedTypeName.get((ClassName)raw, args.toArray(TypeName[]::new));
        }
        List<TypeName> readArguments(){
            if(!take('<')) return List.of();
            var args = new ArrayList<TypeName>();
            do{ args.add(type(true)); }while(take(','));
            if(!take('>')) throw invalid();
            return args;
        }
        String identifier(){
            space();
            int start = position;
            if(position >= text.length() || !Character.isJavaIdentifierStart(text.charAt(position))) throw invalid();
            position++;
            while(position < text.length() && Character.isJavaIdentifierPart(text.charAt(position))) position++;
            String result = text.substring(start, position);
            if(SourceVersion.isKeyword(result)) throw invalid();
            return result;
        }
        boolean word(String word){
            space();
            if(text.startsWith(word, position) && (position + word.length() == text.length()
                || !Character.isJavaIdentifierPart(text.charAt(position + word.length())))){
                position += word.length();
                return true;
            }
            return false;
        }
        boolean take(char value){
            space();
            if(position < text.length() && text.charAt(position) == value){ position++; return true; }
            return false;
        }
        void space(){ while(position < text.length() && Character.isWhitespace(text.charAt(position))) position++; }
        IllegalArgumentException invalid(){ return new IllegalArgumentException("Invalid genericType at position " + position + ": " + text); }
    }
}
