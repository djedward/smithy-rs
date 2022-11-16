/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.traits.LengthTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.RustMetadata
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Visibility
import software.amazon.smithy.rust.codegen.core.rustlang.documentShape
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.util.expectTrait
import software.amazon.smithy.rust.codegen.server.smithy.PubCrateConstraintViolationSymbolProvider
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext

/**
 * [ConstrainedMapGenerator] generates a wrapper tuple newtype holding a constrained `std::collections::HashMap`.
 * This type can be built from unconstrained values, yielding a `ConstraintViolation` when the input does not satisfy
 * the constraints.
 *
 * The [`length` trait] is the only constraint trait applicable to map shapes.
 *
 * If [unconstrainedSymbol] is provided, the `MaybeConstrained` trait is implemented for the constrained type, using the
 * [unconstrainedSymbol]'s associated type as the associated type for the trait.
 *
 * [`length` trait]: https://awslabs.github.io/smithy/1.0/spec/core/constraint-traits.html#length-trait
 */
class ConstrainedMapGenerator(
    val codegenContext: ServerCodegenContext,
    val writer: RustWriter,
    val shape: MapShape,
    private val unconstrainedSymbol: Symbol? = null,
) {
    private val model = codegenContext.model
    private val constrainedShapeSymbolProvider = codegenContext.constrainedShapeSymbolProvider
    private val publicConstrainedTypes = codegenContext.settings.codegenConfig.publicConstrainedTypes
    private val constraintViolationSymbolProvider =
        with(codegenContext.constraintViolationSymbolProvider) {
            if (publicConstrainedTypes) {
                this
            } else {
                PubCrateConstraintViolationSymbolProvider(this)
            }
        }
    private val symbolProvider = codegenContext.symbolProvider

    fun render() {
        // The `length` trait is the only constraint trait applicable to map shapes.
        val lengthTrait = shape.expectTrait<LengthTrait>()

        val name = constrainedShapeSymbolProvider.toSymbol(shape).name
        val inner = "std::collections::HashMap<#{KeySymbol}, #{ValueSymbol}>"
        val constraintViolation = constraintViolationSymbolProvider.toSymbol(shape)

        val condition = if (lengthTrait.min.isPresent && lengthTrait.max.isPresent) {
            "(${lengthTrait.min.get()}..=${lengthTrait.max.get()}).contains(&length)"
        } else if (lengthTrait.min.isPresent) {
            "${lengthTrait.min.get()} <= length"
        } else {
            "length <= ${lengthTrait.max.get()}"
        }

        val constrainedTypeVisibility = if (publicConstrainedTypes) {
            Visibility.PUBLIC
        } else {
            Visibility.PUBCRATE
        }
        val constrainedTypeMetadata = RustMetadata(
            Attribute.Derives(setOf(RuntimeType.Debug, RuntimeType.Clone, RuntimeType.PartialEq)),
            visibility = constrainedTypeVisibility,
        )

        val codegenScope = arrayOf(
            "KeySymbol" to constrainedShapeSymbolProvider.toSymbol(model.expectShape(shape.key.target)),
            "ValueSymbol" to constrainedShapeSymbolProvider.toSymbol(model.expectShape(shape.value.target)),
            "From" to RuntimeType.From,
            "TryFrom" to RuntimeType.TryFrom,
            "ConstraintViolation" to constraintViolation,
        )

        writer.documentShape(shape, model, note = rustDocsNote(name))
        constrainedTypeMetadata.render(writer)
        writer.rustTemplate("struct $name(pub(crate) $inner);", *codegenScope)
        if (constrainedTypeVisibility == Visibility.PUBCRATE) {
            Attribute.AllowUnused.render(writer)
        }
        writer.rustTemplate(
            """
            impl $name {
                /// ${rustDocsInnerMethod(inner)}
                pub fn inner(&self) -> &$inner {
                    &self.0
                }
                
                /// ${rustDocsIntoInnerMethod(inner)}
                pub fn into_inner(self) -> $inner {
                    self.0
                }
            }
            
            impl #{TryFrom}<$inner> for $name {
                type Error = #{ConstraintViolation};
                
                /// ${rustDocsTryFromMethod(name, inner)}
                fn try_from(value: $inner) -> Result<Self, Self::Error> {
                    let length = value.len();
                    if $condition {
                        Ok(Self(value))
                    } else {
                        Err(#{ConstraintViolation}::Length(length))
                    }
                }
            }
            
            impl #{From}<$name> for $inner {
                fn from(value: $name) -> Self {
                    value.into_inner()
                }
            }
            """,
            *codegenScope,
        )

        if (!publicConstrainedTypes && isValueConstrained(shape, model, symbolProvider)) {
            writer.rustTemplate(
                """
                impl #{From}<$name> for #{FullyUnconstrainedSymbol} {
                    fn from(value: $name) -> Self {
                        value
                            .into_inner()
                            .into_iter()
                            .map(|(k, v)| (k, v.into()))
                            .collect()
                    }
                }
                """,
                *codegenScope,
                "FullyUnconstrainedSymbol" to symbolProvider.toSymbol(shape),
            )
        }

        if (unconstrainedSymbol != null) {
            writer.rustTemplate(
                """
                impl #{ConstrainedTrait} for $name  {
                    type Unconstrained = #{UnconstrainedSymbol};
                }
                """,
                "ConstrainedTrait" to RuntimeType.ConstrainedTrait(),
                "UnconstrainedSymbol" to unconstrainedSymbol,
            )
        }
    }
}