package com.bq.autocontainer.compiler


import com.bq.autocontainer.Callback
import com.bq.autocontainer.Plugin
import com.bq.autocontainer.compiler.ProcessorUtils.env
import com.squareup.javapoet.ClassName
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.TypeKindVisitor6

const val CALLBACK_METHOD_CLASS_NAME = "com.bq.autocontainer.CallbackMethod"

class PluginModel(
      val declaringMethod: ExecutableElement,
      val element: TypeElement,
      val fieldName: String) {

   val className: ClassName

   val callbackMethods: List<CallbackMethod>

   init {
      className = ClassName.get(element)
      //Since methods must have unique names in the interfaces this is safe

      if (element.isAbstract) {
         logError("Plugin can't be abstract", element)
      }

      if (element.enclosedElements
            .filter { it.kind == ElementKind.CONSTRUCTOR }
            .map { it as ExecutableElement }
            .firstOrNull { it.modifiers.contains(Modifier.PUBLIC) && it.parameters.isEmpty() } == null) {
         logError("Plugin must have visible empty constructor.")
      }


      callbackMethods = element.allEnclosedElements
            .filter { it.isMethod }
            .filter { it.hasAnnotation(Callback::class.java) }
            .map { CallbackMethod(it as ExecutableElement) }
   }

   enum class CallSuperType {
      BEFORE, AFTER, UNSPECIFIED
   }

   inner class CallbackMethod(val callbackMethod: ExecutableElement) {

      val canOverrideContainerMethod: Boolean
      val callSuper: CallSuperType
      val overrideReturnType: TypeMirror?
      val plugin = this@PluginModel
      val priority: Int

      init {
         canOverrideContainerMethod = callbackMethod.parameters.firstOrNull()
               ?.asType()?.asTypeElementOrNull()
               ?.qualifiedName?.toString()
               ?.equals(CALLBACK_METHOD_CLASS_NAME)
               ?: false

         //If not specified call and the method won't override the container method (lifecycle methods)
         //call it after super, otherwise the callback goes first
         val declaredCallSuperStrategy = let {
            env.elementUtils.getAllAnnotationMirrors(callbackMethod).forEach loop@{
               it.elementValues.entries.forEach { entry ->
                  if (entry.key.simpleName.toString() == "callSuper") {
                     val enumValue = entry.value.toString().substringAfterLast(".")
                     return@let CallSuperType::class.java.enumConstants.first() { it.name == enumValue }
                  }
               }
            }
            CallSuperType.UNSPECIFIED
         }

         val callSuperUnspecified = declaredCallSuperStrategy == CallSuperType.UNSPECIFIED
         callSuper = if (callSuperUnspecified) {
            if (canOverrideContainerMethod) {
               CallSuperType.AFTER
            } else {
               CallSuperType.BEFORE
            }
         } else {
            declaredCallSuperStrategy
         }

         if (canOverrideContainerMethod) {
            overrideReturnType = callbackMethod.parameters.first().asType().accept(object : TypeKindVisitor6<TypeMirror, Void>() {
               override fun visitDeclared(t: DeclaredType, p: Void?): TypeMirror? {
                  return t.typeArguments[0]
               }
            }, null)
         } else {
            overrideReturnType = null
         }

         val callbackAnnotation = callbackMethod.getAnnotation(Callback::class.java)
         val specificPriority = callbackAnnotation.priority
         val basePriority = plugin.element.getAnnotation(Plugin::class.java).priority
         priority = callbackAnnotation.relativePriority +
               if (specificPriority != Integer.MIN_VALUE) specificPriority else basePriority
      }

      fun matchesContainerMethod(containerMethod: ExecutableElement): Boolean {

         val parametersToMatch = callbackMethod.parameters
               .drop(if (canOverrideContainerMethod) 1 else 0) //Drop first if overriding

         val nameMatch = callbackMethod.simpleName == containerMethod.simpleName

         val parametersTypesMatch = parametersToMatch
               .zip(containerMethod.parameters)
               .map { it.first.asType().to(it.second.asType()) }
               .all { it.first.isSameType(it.second) }

         val parametersSizeMatch = parametersToMatch.size == containerMethod.parameters.size

         val returnTypeMatch = if (canOverrideContainerMethod) {
            overrideReturnType!!.implements(containerMethod.returnType)
                  || containerMethod.returnType.kind == TypeKind.VOID && overrideReturnType.implements(elementForName("java.lang.Void").asType())
         } else {
            // We don't care about return type in callback methods since its discarded
            // and there is no way to mutate the caller other than the arguments
            true
         }

         return nameMatch && returnTypeMatch && parametersTypesMatch && parametersSizeMatch
      }

      override fun toString(): String {
         return "CallbackMethod(callbackMethod=$callbackMethod, canOverrideContainerMethod=$canOverrideContainerMethod, callSuper=$callSuper, returnType=$overrideReturnType)"
      }
   }

   override fun toString(): String {
      return "PluginModel(fieldName='$fieldName', className=$className, callbackMethods=${callbackMethods.toString().replace(",", "\n")})"
   }
}

