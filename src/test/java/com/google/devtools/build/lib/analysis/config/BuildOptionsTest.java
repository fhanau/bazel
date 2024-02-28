// Copyright 2009 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis.config;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableClassToInstanceMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.config.BuildOptions.MapBackedChecksumCache;
import com.google.devtools.build.lib.analysis.config.BuildOptions.OptionsChecksumCache;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.android.AndroidConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppOptions;
import com.google.devtools.build.lib.rules.java.JavaOptions;
import com.google.devtools.build.lib.rules.proto.ProtoConfiguration;
import com.google.devtools.build.lib.rules.python.PythonOptions;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.common.options.OptionsParser;
import com.google.protobuf.ByteString;
import java.util.AbstractMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * A test for {@link BuildOptions}.
 *
 * <p>Currently this tests native options and Starlark options completely separately since these two
 * types of options do not interact. In the future when we begin to migrate native options to
 * Starlark options, the format of this test class will need to accommodate that overlap.
 */
@RunWith(JUnit4.class)
public final class BuildOptionsTest {

  private static final ImmutableList<Class<? extends FragmentOptions>> BUILD_CONFIG_OPTIONS =
      ImmutableList.of(CoreOptions.class);

  @Test
  public void optionSetCaching() {
    BuildOptions a =
        BuildOptions.of(
            BUILD_CONFIG_OPTIONS,
            OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build());
    BuildOptions b =
        BuildOptions.of(
            BUILD_CONFIG_OPTIONS,
            OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build());
    // The cache keys of the OptionSets must be equal even if these are
    // different objects, if they were created with the same options (no options in this case).
    assertThat(b.toString()).isEqualTo(a.toString());
    assertThat(b.checksum()).isEqualTo(a.checksum());
    assertThat(a).isEqualTo(b);
  }

  @Test
  public void cachingSpecialCases() throws Exception {
    // You can add options here to test that their string representations are good.
    String[] options = new String[] {"--run_under=//run_under"};
    BuildOptions a = BuildOptions.of(BUILD_CONFIG_OPTIONS, options);
    BuildOptions b = BuildOptions.of(BUILD_CONFIG_OPTIONS, options);
    assertThat(b.toString()).isEqualTo(a.toString());
  }

  @Test
  public void optionsEquality() throws Exception {
    String[] options1 = new String[] {"--compilation_mode=opt"};
    String[] options2 = new String[] {"--compilation_mode=dbg"};
    // Distinct instances with the same values are equal:
    assertThat(BuildOptions.of(BUILD_CONFIG_OPTIONS, options1))
        .isEqualTo(BuildOptions.of(BUILD_CONFIG_OPTIONS, options1));
    // Same fragments, different values aren't equal:
    assertThat(
            BuildOptions.of(BUILD_CONFIG_OPTIONS, options1)
                .equals(BuildOptions.of(BUILD_CONFIG_OPTIONS, options2)))
        .isFalse();
    // Same values, different fragments aren't equal:
    assertThat(
            BuildOptions.of(BUILD_CONFIG_OPTIONS, options1)
                .equals(
                    BuildOptions.of(
                        ImmutableList.of(CoreOptions.class, CppOptions.class), options1)))
        .isFalse();
  }

  @Test
  public void serialization() throws Exception {
    new SerializationTester(
            BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=opt", "cpu=k8"),
            BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=dbg", "cpu=k8"),
            BuildOptions.of(
                makeOptionsClassBuilder()
                    .add(AndroidConfiguration.Options.class)
                    .add(ProtoConfiguration.Options.class)
                    .build()),
            BuildOptions.of(
                makeOptionsClassBuilder().add(JavaOptions.class).add(PythonOptions.class).build(),
                "--cpu=k8",
                "--compilation_mode=opt",
                "--compiler=gcc",
                "--copt=-Dfoo",
                "--javacopt=--javacoption",
                "--experimental_fix_deps_tool=fake",
                "--build_python_zip=no",
                "--python_version=PY2"))
        .addDependency(OptionsChecksumCache.class, new MapBackedChecksumCache())
        .runTests();
  }

  private static ImmutableList.Builder<Class<? extends FragmentOptions>> makeOptionsClassBuilder() {
    return ImmutableList.<Class<? extends FragmentOptions>>builder()
        .addAll(BUILD_CONFIG_OPTIONS)
        .add(CppOptions.class);
  }

  @Test
  public void serialize_primeFails_throws() throws Exception {
    OptionsChecksumCache failToPrimeCache =
        new OptionsChecksumCache() {
          @Override
          public BuildOptions getOptions(String checksum) {
            throw new UnsupportedOperationException();
          }

          @Override
          public boolean prime(BuildOptions options) {
            return false;
          }
        };
    BuildOptions options = BuildOptions.of(BUILD_CONFIG_OPTIONS);
    ObjectCodecs codecs =
        new ObjectCodecs(
            ImmutableClassToInstanceMap.of(OptionsChecksumCache.class, failToPrimeCache));
    assertThrows(SerializationException.class, () -> codecs.serialize(options));
  }

  @Test
  public void deserialize_unprimedCache_throws() throws Exception {
    BuildOptions options = BuildOptions.of(BUILD_CONFIG_OPTIONS);

    ObjectCodecs codecs =
        new ObjectCodecs(
            ImmutableClassToInstanceMap.of(
                OptionsChecksumCache.class, new MapBackedChecksumCache()));
    ByteString bytes = codecs.serialize(options);
    assertThat(bytes).isNotNull();

    // Different checksum cache than the one used for serialization, and it has not been primed.
    ObjectCodecs notPrimed =
        new ObjectCodecs(
            ImmutableClassToInstanceMap.of(
                OptionsChecksumCache.class, new MapBackedChecksumCache()));
    Exception e = assertThrows(SerializationException.class, () -> notPrimed.deserialize(bytes));
    assertThat(e).hasMessageThat().contains(options.checksum());
  }

  @Test
  public void deserialize_primedCache_returnsPrimedInstance() throws Exception {
    BuildOptions options = BuildOptions.of(BUILD_CONFIG_OPTIONS);

    ObjectCodecs codecs =
        new ObjectCodecs(
            ImmutableClassToInstanceMap.of(
                OptionsChecksumCache.class, new MapBackedChecksumCache()));
    ByteString bytes = codecs.serialize(options);
    assertThat(bytes).isNotNull();

    // Different checksum cache than the one used for serialization, but it has been primed.
    OptionsChecksumCache checksumCache = new MapBackedChecksumCache();
    assertThat(checksumCache.prime(options)).isTrue();
    ObjectCodecs primed =
        new ObjectCodecs(ImmutableClassToInstanceMap.of(OptionsChecksumCache.class, checksumCache));
    assertThat(primed.deserialize(bytes)).isSameInstanceAs(options);
  }

  @Test
  public void testMultiValueOptionImmutability() {
    BuildOptions options =
        BuildOptions.of(
            BUILD_CONFIG_OPTIONS,
            OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build());
    CoreOptions coreOptions = options.get(CoreOptions.class);
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            coreOptions.commandLineBuildVariables.add(new AbstractMap.SimpleEntry<>("foo", "bar")));
  }

  @Test
  public void parsingResultTransform() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--cpu=foo", "--stamp");

    OptionsParser parser = OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build();
    parser.parse("--cpu=bar", "--nostamp");
    parser.setStarlarkOptions(ImmutableMap.of("//custom:flag", "hello"));

    BuildOptions modified = original.applyParsingResult(parser);

    assertThat(original.get(CoreOptions.class).cpu)
        .isNotEqualTo(modified.get(CoreOptions.class).cpu);
    assertThat(modified.get(CoreOptions.class).cpu).isEqualTo("bar");
    assertThat(modified.get(CoreOptions.class).stampBinaries).isFalse();
    assertThat(modified.getStarlarkOptions().get(Label.parseCanonicalUnchecked("//custom:flag")))
        .isEqualTo("hello");
  }

  @Test
  public void parsingResultTransformNativeIgnored() throws Exception {
    ImmutableList.Builder<Class<? extends FragmentOptions>> fragmentClassesBuilder =
        ImmutableList.<Class<? extends FragmentOptions>>builder().add(CoreOptions.class);

    BuildOptions original = BuildOptions.of(fragmentClassesBuilder.build());

    fragmentClassesBuilder.add(CppOptions.class);

    OptionsParser parser =
        OptionsParser.builder().optionsClasses(fragmentClassesBuilder.build()).build();
    parser.parse("--cxxopt=bar");

    BuildOptions modified = original.applyParsingResult(parser);

    assertThat(modified.contains(CppOptions.class)).isFalse();
  }

  @Test
  public void parsingResultTransformIllegalStarlarkLabel() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS);

    OptionsParser parser = OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build();
    parser.setStarlarkOptions(ImmutableMap.of("@@@", "hello"));

    assertThrows(IllegalArgumentException.class, () -> original.applyParsingResult(parser));
  }

  @Test
  public void parsingResultTransformMultiValueOption() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS);

    OptionsParser parser = OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build();
    parser.parse("--features=foo");

    BuildOptions modified = original.applyParsingResult(parser);

    assertThat(modified.get(CoreOptions.class).defaultFeatures).containsExactly("foo");
  }

  @Test
  public void parsingResultMatch() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--cpu=foo", "--stamp");

    OptionsParser matchingParser =
        OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build();
    matchingParser.parse("--cpu=foo", "--stamp");

    OptionsParser notMatchingParser =
        OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build();
    notMatchingParser.parse("--cpu=foo", "--nostamp");

    assertThat(original.matches(matchingParser)).isTrue();
    assertThat(original.matches(notMatchingParser)).isFalse();
  }

  @Test
  public void parsingResultMatchStarlark() throws Exception {
    BuildOptions original =
        BuildOptions.builder()
            .addStarlarkOption(Label.parseCanonicalUnchecked("//custom:flag"), "hello")
            .build();

    OptionsParser matchingParser =
        OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build();
    matchingParser.setStarlarkOptions(ImmutableMap.of("//custom:flag", "hello"));

    OptionsParser notMatchingParser =
        OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build();
    notMatchingParser.setStarlarkOptions(ImmutableMap.of("//custom:flag", "foo"));

    assertThat(original.matches(matchingParser)).isTrue();
    assertThat(original.matches(notMatchingParser)).isFalse();
  }

  @Test
  public void parsingResultMatchMissingFragment() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--cpu=foo");

    ImmutableList<Class<? extends FragmentOptions>> fragmentClasses =
        ImmutableList.<Class<? extends FragmentOptions>>builder()
            .add(CoreOptions.class)
            .add(CppOptions.class)
            .build();

    OptionsParser parser = OptionsParser.builder().optionsClasses(fragmentClasses).build();
    parser.parse("--cpu=foo", "--cxxopt=bar");

    assertThat(original.matches(parser)).isTrue();
  }

  @Test
  public void parsingResultMatchEmptyNativeMatch() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--cpu=foo");

    ImmutableList<Class<? extends FragmentOptions>> fragmentClasses =
        ImmutableList.<Class<? extends FragmentOptions>>builder()
            .add(CoreOptions.class)
            .add(CppOptions.class)
            .build();

    OptionsParser parser = OptionsParser.builder().optionsClasses(fragmentClasses).build();
    parser.parse("--cxxopt=bar");

    assertThat(original.matches(parser)).isFalse();
  }

  @Test
  public void parsingResultMatchEmptyNativeMatchWithStarlark() throws Exception {
    BuildOptions original =
        BuildOptions.builder()
            .addStarlarkOption(Label.parseCanonicalUnchecked("//custom:flag"), "hello")
            .build();

    ImmutableList<Class<? extends FragmentOptions>> fragmentClasses =
        ImmutableList.<Class<? extends FragmentOptions>>builder()
            .add(CoreOptions.class)
            .add(CppOptions.class)
            .build();

    OptionsParser parser = OptionsParser.builder().optionsClasses(fragmentClasses).build();
    parser.parse("--cxxopt=bar");
    parser.setStarlarkOptions(ImmutableMap.of("//custom:flag", "hello"));

    assertThat(original.matches(parser)).isTrue();
  }

  @Test
  public void parsingResultMatchStarlarkOptionMissing() throws Exception {
    BuildOptions original =
        BuildOptions.builder()
            .addStarlarkOption(Label.parseCanonicalUnchecked("//custom:flag1"), "hello")
            .build();

    OptionsParser parser = OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build();
    parser.setStarlarkOptions(ImmutableMap.of("//custom:flag2", "foo"));

    assertThat(original.matches(parser)).isFalse();
  }

  @Test
  public void parsingResultMatchNullOption() throws Exception {
    BuildOptions original = BuildOptions.of(BUILD_CONFIG_OPTIONS);

    OptionsParser parser = OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build();
    parser.parse("--platform_suffix=foo"); // Note: platform_suffix is null by default.

    assertThat(original.matches(parser)).isFalse();
  }
}
