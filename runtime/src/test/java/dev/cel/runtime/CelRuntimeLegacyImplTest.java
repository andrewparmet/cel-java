// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.cel.runtime;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Message;
import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelException;
import dev.cel.common.CelOptions;
import dev.cel.common.exceptions.CelDivideByZeroException;
import dev.cel.common.types.StructTypeReference;
import dev.cel.common.values.CelValueProvider;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.expr.conformance.proto3.TestAllTypes;
import dev.cel.runtime.CelStandardFunctions.StandardFunction;
import java.util.Optional;
import java.util.function.Function;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CelRuntimeLegacyImplTest {

  @Test
  public void evalException() throws CelException {
    CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder().build();
    CelRuntime runtime = CelRuntimeFactory.standardCelRuntimeBuilder().build();
    CelRuntime.Program program = runtime.createProgram(compiler.compile("1/0").getAst());
    CelEvaluationException e = Assert.assertThrows(CelEvaluationException.class, program::eval);
    assertThat(e).hasCauseThat().isInstanceOf(CelDivideByZeroException.class);
  }

  @Test
  public void toRuntimeBuilder_isNewInstance() {
    CelRuntimeBuilder celRuntimeBuilder = CelRuntimeFactory.standardCelRuntimeBuilder();
    CelRuntimeLegacyImpl celRuntime = (CelRuntimeLegacyImpl) celRuntimeBuilder.build();

    CelRuntimeLegacyImpl.Builder newRuntimeBuilder =
        (CelRuntimeLegacyImpl.Builder) celRuntime.toRuntimeBuilder();

    assertThat(newRuntimeBuilder).isNotEqualTo(celRuntimeBuilder);
  }

  @Test
  public void toRuntimeBuilder_isImmutable() {
    CelRuntimeBuilder originalRuntimeBuilder = CelRuntimeFactory.standardCelRuntimeBuilder();
    CelRuntimeLegacyImpl celRuntime = (CelRuntimeLegacyImpl) originalRuntimeBuilder.build();
    originalRuntimeBuilder.addLibraries(runtimeBuilder -> {});

    CelRuntimeLegacyImpl.Builder newRuntimeBuilder =
        (CelRuntimeLegacyImpl.Builder) celRuntime.toRuntimeBuilder();

    assertThat(newRuntimeBuilder.celRuntimeLibraries.build()).isEmpty();
  }

  @Test
  public void toRuntimeBuilder_collectionProperties_copied() {
    CelRuntimeBuilder celRuntimeBuilder = CelRuntimeFactory.standardCelRuntimeBuilder();
    celRuntimeBuilder.addMessageTypes(TestAllTypes.getDescriptor());
    celRuntimeBuilder.addFileTypes(TestAllTypes.getDescriptor().getFile());
    celRuntimeBuilder.addFunctionBindings(CelFunctionBinding.from("test", Integer.class, arg -> 1));
    celRuntimeBuilder.addLibraries(runtimeBuilder -> {});
    int originalFileTypesSize =
        ((CelRuntimeLegacyImpl.Builder) celRuntimeBuilder).fileTypes.build().size();
    CelRuntimeLegacyImpl celRuntime = (CelRuntimeLegacyImpl) celRuntimeBuilder.build();

    CelRuntimeLegacyImpl.Builder newRuntimeBuilder =
        (CelRuntimeLegacyImpl.Builder) celRuntime.toRuntimeBuilder();

    assertThat(newRuntimeBuilder.customFunctionBindings).hasSize(1);
    assertThat(newRuntimeBuilder.celRuntimeLibraries.build()).hasSize(1);
    assertThat(newRuntimeBuilder.fileTypes.build()).hasSize(originalFileTypesSize);
  }

  @Test
  public void toRuntimeBuilder_collectionProperties_areImmutable() {
    CelRuntimeBuilder celRuntimeBuilder = CelRuntimeFactory.standardCelRuntimeBuilder();
    CelRuntimeLegacyImpl celRuntime = (CelRuntimeLegacyImpl) celRuntimeBuilder.build();
    CelRuntimeLegacyImpl.Builder newRuntimeBuilder =
        (CelRuntimeLegacyImpl.Builder) celRuntime.toRuntimeBuilder();

    // Mutate the original builder containing collections
    celRuntimeBuilder.addMessageTypes(TestAllTypes.getDescriptor());
    celRuntimeBuilder.addFileTypes(TestAllTypes.getDescriptor().getFile());
    celRuntimeBuilder.addFunctionBindings(CelFunctionBinding.from("test", Integer.class, arg -> 1));
    celRuntimeBuilder.addLibraries(runtimeBuilder -> {});

    assertThat(newRuntimeBuilder.customFunctionBindings).isEmpty();
    assertThat(newRuntimeBuilder.celRuntimeLibraries.build()).isEmpty();
    assertThat(newRuntimeBuilder.fileTypes.build()).isEmpty();
  }

  @Test
  public void toRuntimeBuilder_optionalProperties() {
    Function<String, Message.Builder> customTypeFactory = (typeName) -> TestAllTypes.newBuilder();
    CelStandardFunctions overriddenStandardFunctions =
        CelStandardFunctions.newBuilder().includeFunctions(StandardFunction.ADD).build();
    CelValueProvider noOpValueProvider = (structType, fields) -> Optional.empty();
    CelRuntimeBuilder celRuntimeBuilder =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .setStandardEnvironmentEnabled(false)
            .setTypeFactory(customTypeFactory)
            .setStandardFunctions(overriddenStandardFunctions)
            .setValueProvider(noOpValueProvider);
    CelRuntime celRuntime = celRuntimeBuilder.build();

    CelRuntimeLegacyImpl.Builder newRuntimeBuilder =
        (CelRuntimeLegacyImpl.Builder) celRuntime.toRuntimeBuilder();

    assertThat(newRuntimeBuilder.customTypeFactory).isEqualTo(customTypeFactory);
    assertThat(newRuntimeBuilder.overriddenStandardFunctions)
        .isEqualTo(overriddenStandardFunctions);
    assertThat(newRuntimeBuilder.celValueProvider).isEqualTo(noOpValueProvider);
  }

  // When enableCelValue(true) is set on the legacy runtime, CelRuntimeLegacyImpl falls back to the
  // default ProtoMessageValueProvider (not provided by the user) and passes it to
  // CelValueRuntimeTypeProvider.newInstance. That method needs to extract the underlying
  // BaseProtoCelValueConverter so raw Messages can be wrapped as SelectableValues for field
  // access.
  //
  // ProtoMessageValueProvider previously extended BaseProtoMessageValueProvider, so the
  // instanceof check in newInstance caught it. It was later refactored to implement
  // CelValueProvider directly, and the check in newInstance wasn't updated — so the default
  // pass-through converter got installed, and any field access on a Message variable failed with
  // "Field selections must be performed on messages or maps." Regression-guard with a simple
  // end-to-end evaluation that exercises the default-provider path.
  @Test
  public void evaluate_enableCelValue_defaultProtoMessageValueProvider_fieldAccessSucceeds()
      throws Exception {
    Cel cel =
        CelFactory.standardCelBuilder()
            .setOptions(CelOptions.current().enableCelValue(true).build())
            .addVar(
                "msg", StructTypeReference.create(TestAllTypes.getDescriptor().getFullName()))
            .addMessageTypes(TestAllTypes.getDescriptor())
            .build();
    CelAbstractSyntaxTree ast = cel.compile("msg.single_int64").getAst();
    CelRuntime.Program program = cel.createProgram(ast);

    Object evaluatedResult =
        program.eval(ImmutableMap.of("msg", TestAllTypes.newBuilder().setSingleInt64(42L).build()));

    assertThat(evaluatedResult).isEqualTo(42L);
  }
}
