package com.bq.autocontainer.compiler

import com.bq.autocontainer.AutoContainer
import com.bq.autocontainer.Plugin
import com.bq.autocontainer.compiler.ProcessorUtils.env
import com.squareup.javapoet.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.type.TypeMirror


class ComponentModel(val element: TypeElement) {

   var generated = false

   val plugins: List<PluginModel>
   val scopeClass: TypeMirror
   val originalClassName: ClassName
   val generatedClassName: ClassName
   val moduleClassName: ClassName


   val containerModel: ContainerModel

   init {
      originalClassName = ClassName.get(element)

      generatedClassName = ClassName.get(
            originalClassName.packageName(),
            originalClassName.simpleName() + "Component")

      moduleClassName = ClassName.get(
            originalClassName.packageName(),
            originalClassName.simpleName() + "Module")

      val annotation = element.getAnnotation(AutoContainer::class.java)
      scopeClass = annotation.typeMirror(AutoContainer::scope)

      plugins = element.enclosedElements
            .filter { it.kind == ElementKind.METHOD }
            .map { it as ExecutableElement }
            .filter { it.returnType.asElement().hasAnnotation(Plugin::class.java) }
            .map {
               PluginModel(
                     it,
                     it.returnType.asElement().asTypeElement(),
                     it.simpleName.toString())
            }

      containerModel = ContainerModel(originalClassName, element, plugins)
   }

   fun generateClass() {
      if (generated) return
      generated = true
      generateComponentClass()
      generateModuleClass()
      generateActivityClass()
   }

   private fun generateComponentClass() {
      val componentTypeSpec = TypeSpec.interfaceBuilder(generatedClassName)
            .addModifiers(Modifier.PUBLIC)

      //Replace @AutoContainer Annotation with @Component, everything else is the same
      val componentAnnotations = element.copyAnnotations()
      componentAnnotations.forEachIndexed { i, annotationSpec ->
         if (annotationSpec.type.equals(ClassName.get(AutoContainer::class.java))) {
            val builder = AnnotationSpec.builder(Component::class.java)
            annotationSpec.members
                  //Only keep @Component values
                  .filter { it.key == "modules" || it.key == "dependencies" }
                  .forEach { member ->
                     member.value.forEach {
                        builder.addMember(member.key, it)
                     }
                  }

            //Add the generated module
            builder.addMember("modules", "\$T.class", moduleClassName)
            componentAnnotations[i] = builder.build()
         }
      }

      //Add original annotations
      componentTypeSpec
            .addAnnotations(componentAnnotations.asIterable())
            .addAnnotation(scopeClass.toClassName())

      //Copy all the methods
      element.asTypeElement().enclosedAndInheritedElements()
            .filter { it.kind == ElementKind.METHOD }
            .map { it as ExecutableElement }
            .forEach { method ->
               componentTypeSpec.addMethod(MethodSpec.methodBuilder(method.simpleName.toString())
                     .addAnnotations(method.copyAnnotations().asIterable())
                     .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                     .returns(ClassName.get(method.returnType))
                     .apply {
                        if (plugins.map { it.declaringMethod }.contains(method))
                           addAnnotation(scopeClass.toClassName())
                     }
                     .build()
               )
            }

      //Generate injection points for unique types (plugins and activity)
      plugins.map { it.className }
            .distinct()
            .plus(containerModel.containerClassName)
            .forEach {
               componentTypeSpec.addMethod(MethodSpec.methodBuilder("inject")
                     .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                     .addParameter(it, it.simpleName().toLowerCase())
                     .returns(TypeName.VOID)
                     .build()
               )
            }

      val file = JavaFile.builder(generatedClassName.packageName(), componentTypeSpec.build()).build()
      file.writeTo(env.filer)
   }

   private fun generateModuleClass() {
      val componentTypeSpec = TypeSpec.classBuilder(moduleClassName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Module::class.java)

      //Add provision methods for plugins
      plugins.forEach {
         componentTypeSpec.addMethod(
               MethodSpec.methodBuilder("provide${it.declaringMethod.simpleName.toString().capitalize()}")
                     .addModifiers(Modifier.PUBLIC)
                     .addAnnotation(Provides::class.java)
                     .addAnnotations(it.declaringMethod.copyAnnotations().asIterable())
                     .addAnnotation(scopeClass.toClassName())
                     .returns(it.className)
                     .addStatement("return new \$T()", it.className)
                     .build()
         )
      }

      //Add provision methods for Activity
      componentTypeSpec
            .addField(containerModel.containerClassName, "container",
                  Modifier.PRIVATE,
                  Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                  .addModifiers(Modifier.PUBLIC)
                  .addParameter(containerModel.containerClassName, "container")
                  .addStatement("this.container = container")
                  .build())
            .addMethod(
                  MethodSpec.methodBuilder("provideContainer")
                        .addModifiers(Modifier.PUBLIC)
                        .addAnnotation(Provides::class.java)
                        .addStatement("return this.container")
                        .returns(containerModel.containerClassName)
                        .build()
            )

      val file = JavaFile.builder(moduleClassName.packageName(), componentTypeSpec.build()).build()
      file.writeTo(env.filer)
   }

   private fun generateActivityClass() {
      containerModel.generateClass()
   }
}