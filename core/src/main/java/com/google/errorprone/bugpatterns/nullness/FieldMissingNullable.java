/*
 * Copyright 2016 The Error Prone Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.errorprone.bugpatterns.nullness;

import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.bugpatterns.nullness.NullnessFixes.getNullCheck;
import static com.google.errorprone.bugpatterns.nullness.ReturnMissingNullable.hasDefinitelyNullBranch;
import static com.google.errorprone.bugpatterns.nullness.ReturnMissingNullable.varsProvenNullByParentIf;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static com.google.errorprone.util.ASTHelpers.getSymbol;
import static javax.lang.model.element.ElementKind.FIELD;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.AssignmentTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.BinaryTreeMatcher;
import com.google.errorprone.bugpatterns.BugChecker.VariableTreeMatcher;
import com.google.errorprone.bugpatterns.nullness.NullnessFixes.NullCheck;
import com.google.errorprone.dataflow.nullnesspropagation.Nullness;
import com.google.errorprone.dataflow.nullnesspropagation.NullnessAnnotations;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import javax.annotation.Nullable;
import javax.lang.model.element.Name;

/** A {@link BugChecker}; see the associated {@link BugPattern} annotation for details. */
@BugPattern(
    name = "FieldMissingNullable",
    summary =
        "Field is assigned (or compared against) a definitely null value but is not annotated"
            + " @Nullable",
    severity = SUGGESTION)
public class FieldMissingNullable extends BugChecker
    implements BinaryTreeMatcher, AssignmentTreeMatcher, VariableTreeMatcher {
  /*
   * TODO(cpovirk): Consider providing a flag to make this checker still more conservative, similar
   * to what we provide in ReturnMissingNullable. In particular, consider skipping fields whose type
   * is a type-variable usage.
   */

  @Override
  public Description matchBinary(BinaryTree tree, VisitorState state) {
    NullCheck nullCheck = getNullCheck(tree);
    if (nullCheck == null) {
      return NO_MATCH;
    }
    // TODO(cpovirk): Consider not adding @Nullable in cases like `checkState(foo != null)`.
    return matchIfLocallyDeclaredReferenceFieldWithoutNullable(
        /*
         * We do want the Symbol here: We conclude that a field may be null if there is code that
         * compares *any* access of the field (foo, this.foo, other.foo) to null.
         */
        nullCheck.varSymbolButUsuallyPreferBareIdentifier(), tree, state);
  }

  @Override
  public Description matchAssignment(AssignmentTree tree, VisitorState state) {
    return match(getSymbol(tree.getVariable()), tree.getExpression(), state);
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    return match(getSymbol(tree), tree.getInitializer(), state);
  }

  private Description match(Symbol assigned, ExpressionTree expression, VisitorState state) {
    if (expression == null) {
      return NO_MATCH;
    }

    ImmutableSet<Name> varsProvenNullByParentIf =
        varsProvenNullByParentIf(
            /*
             * Start at the AssignmentTree/VariableTree, not its expression. This matches what we do
             * for ReturnMissingNullable, where we start at the ReturnTree and not its expression.
             */
            state.getPath().getParentPath());
    if (!hasDefinitelyNullBranch(
        expression,
        // TODO(cpovirk): Precompute a set of definitelyNullVars instead of passing an empty set.
        ImmutableSet.of(),
        varsProvenNullByParentIf,
        state)) {
      return NO_MATCH;
    }

    return matchIfLocallyDeclaredReferenceFieldWithoutNullable(assigned, expression, state);
  }

  private Description matchIfLocallyDeclaredReferenceFieldWithoutNullable(
      Symbol assigned, ExpressionTree treeToReportOn, VisitorState state) {
    if (assigned == null || assigned.getKind() != FIELD || assigned.type.isPrimitive()) {
      return NO_MATCH;
    }

    if (NullnessAnnotations.fromAnnotationsOn(assigned).orElse(null) == Nullness.NULLABLE) {
      return NO_MATCH;
    }

    VariableTree fieldDecl = findDeclaration(state, assigned);
    if (fieldDecl == null) {
      return NO_MATCH;
    }

    return describeMatch(treeToReportOn, NullnessFixes.makeFix(state, fieldDecl));
  }

  // TODO(cpovirk): Move this somewhere sensible, maybe into a renamed NullnessFixes?
  @Nullable
  static VariableTree findDeclaration(VisitorState state, Symbol sym) {
    JavacProcessingEnvironment javacEnv = JavacProcessingEnvironment.instance(state.context);
    TreePath declPath = Trees.instance(javacEnv).getPath(sym);
    // Skip fields declared in other compilation units since we can't make a fix for them here.
    if (declPath != null
        && declPath.getCompilationUnit() == state.getPath().getCompilationUnit()
        && (declPath.getLeaf() instanceof VariableTree)) {
      return (VariableTree) declPath.getLeaf();
    }
    return null;
  }
}
