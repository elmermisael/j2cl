/*
 * Copyright 2022 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.backend.kotlin

import com.google.j2cl.transpiler.ast.ArrayTypeDescriptor
import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor
import com.google.j2cl.transpiler.ast.IntersectionTypeDescriptor
import com.google.j2cl.transpiler.ast.MethodDescriptor
import com.google.j2cl.transpiler.ast.PrimitiveTypeDescriptor
import com.google.j2cl.transpiler.ast.TypeDescriptor
import com.google.j2cl.transpiler.ast.TypeDescriptors.isJavaLangObject
import com.google.j2cl.transpiler.ast.TypeVariable
import com.google.j2cl.transpiler.ast.UnionTypeDescriptor

/** Returns non-raw version of this type descriptor. */
internal fun TypeDescriptor.toNonRaw(
  projectToWildcards: Boolean = false,
  seenRawTypeDescriptors: Set<DeclaredTypeDescriptor> = setOf()
): TypeDescriptor = rewriteDeclared {
  val arguments =
    if (!isRaw) {
      typeArgumentDescriptors
    } else {
      // Convert type arguments to variables.
      val unparametrizedTypeDescriptor = toUnparameterizedTypeDescriptor()

      // Find variables which will be projected to bounds, others will be projected to
      // wildcards.
      val boundProjectedVariables =
        if (projectToWildcards) setOf()
        else unparametrizedTypeDescriptor.typeArgumentDescriptors.map { it as TypeVariable }.toSet()

      val seenRawTypeDescriptorsPlusThis = seenRawTypeDescriptors + this

      // Replace variables with bounds or wildcards.
      val projectedTypeDescriptor =
        unparametrizedTypeDescriptor.specializeTypeVariables { variable ->
          val upperBound = variable.upperBoundTypeDescriptor.toRawTypeDescriptor()!!
          val isRecursive =
            upperBound is DeclaredTypeDescriptor &&
              seenRawTypeDescriptorsPlusThis.contains(upperBound)
          val projectToBounds = boundProjectedVariables.contains(variable) && !isRecursive
          if (projectToBounds)
            upperBound.toNonRaw(
              projectToWildcards = true,
              seenRawTypeDescriptors = seenRawTypeDescriptorsPlusThis
            )
          else TypeVariable.createWildcard()
        }

      projectedTypeDescriptor.typeArgumentDescriptors
    }

  DeclaredTypeDescriptor.Builder.from(this).setTypeArgumentDescriptors(arguments).build()
}

/** Recursively rewrite type descriptors using {@code fn}. */
private fun TypeDescriptor.rewrite(fn: TypeDescriptor.() -> TypeDescriptor): TypeDescriptor =
  when (this) {
    is PrimitiveTypeDescriptor -> this
    is TypeVariable ->
      if (!isWildcardOrCapture) this // Preserve type variables
      else
        TypeVariable.Builder.from(this)
          .setLowerBoundTypeDescriptor(lowerBoundTypeDescriptor?.rewrite(fn))
          .setUpperBoundTypeDescriptorSupplier { upperBoundTypeDescriptor.rewrite(fn) }
          .build()
    is ArrayTypeDescriptor ->
      ArrayTypeDescriptor.Builder.from(this)
        .setComponentTypeDescriptor(componentTypeDescriptor?.rewrite(fn))
        .build()
    is UnionTypeDescriptor ->
      UnionTypeDescriptor.newBuilder()
        .setUnionTypeDescriptors(unionTypeDescriptors.map { it.rewrite(fn) })
        .build()
    is IntersectionTypeDescriptor ->
      IntersectionTypeDescriptor.newBuilder()
        .setIntersectionTypeDescriptors(
          intersectionTypeDescriptors.map { it.rewrite(fn) as DeclaredTypeDescriptor }
        )
        .build()
    is DeclaredTypeDescriptor ->
      DeclaredTypeDescriptor.Builder.from(this)
        .setEnclosingTypeDescriptor(enclosingTypeDescriptor?.rewrite(fn) as DeclaredTypeDescriptor?)
        .setTypeArgumentDescriptors(typeArgumentDescriptors.map { it.rewrite(fn) })
        .build()
    else -> error("Unhandled $this")
  }.fn()

/** Recursively rewrite declared type descriptors using {@code fn}. */
private fun TypeDescriptor.rewriteDeclared(
  fn: DeclaredTypeDescriptor.() -> DeclaredTypeDescriptor
) = rewrite {
  when (this) {
    is DeclaredTypeDescriptor -> fn()
    else -> this
  }
}

internal val TypeDescriptor.isImplicitUpperBound
  get() = isJavaLangObject(this) && isNullable

// TODO(b/216796920): Remove when the bug is fixed.
internal val DeclaredTypeDescriptor.directlyDeclaredTypeArgumentDescriptors: List<TypeDescriptor>
  get() = typeArgumentDescriptors.take(typeDeclaration.directlyDeclaredTypeParameterCount)

/** Returns direct super type to use for super method call. */
internal fun DeclaredTypeDescriptor.directSuperTypeForMethodCall(
  methodDescriptor: MethodDescriptor
): DeclaredTypeDescriptor? =
  superTypesStream
    .map { superType ->
      // See if the method is in this supertype (in which case we are done) or if it is
      // overridden here (in which case this supertype is not the target).
      val declaredSuperMethodDescriptor =
        superType.declaredMethodDescriptors.find {
          it == methodDescriptor || it.isOverride(methodDescriptor)
        }
      when (declaredSuperMethodDescriptor) {
        // The method has not been found nor it is overridden in this supertype so continue looking
        // up the hierarchy; so if we find it up the hierarchy this is the supertype to return.
        null -> superType.takeIf { it.directSuperTypeForMethodCall(methodDescriptor) != null }
        // We found the implementation targeted, so return this supertype.
        methodDescriptor -> superType
        // We found an override of the method in the hierarchy, so this supertype is not providing
        // the implementation targeted.
        else -> null
      }
    }
    .filter { it != null }
    .findFirst()
    .orElse(null)
