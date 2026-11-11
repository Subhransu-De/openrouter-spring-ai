package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import de.subhransu.openrouter.springai.garage.rulefixtures.TestOnlyOtherPackageCallers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves that the rule in {@link TestOnlyProductionCodeTests} fails on a test-only member
 * and on a member widened for tests, accepts each exemption, and stays narrow: every
 * exemption has a twin member that it must not cover. This class and the classes of the
 * {@code rulefixtures} package play the test callers; the other fixtures are the
 * production code.
 */
class TestOnlyProductionCodeRuleTests {

	private static final JavaClasses FIXTURES = new ClassFileImporter().importClasses(fixtureTypes());

	static Stream<Arguments> rejectedFixtures() {
		return Stream.of(
				rejected("test-only member", TestOnlyProductionFixtures.TestOnly.class,
						"$TestOnly.onlyTestsCallMe()> is used only by tests"),
				rejected("test-only field", TestOnlyProductionFixtures.TestOnlyField.class,
						"$TestOnlyField.value> is used only by tests"),
				rejected("test-only constructor", TestOnlyProductionFixtures.TestOnlyConstructor.class,
						"$TestOnlyConstructor.<init>(int)> is used only by tests"),
				rejected("member widened for tests", TestOnlyProductionFixtures.Widened.class,
						"$Widened.used()> is PACKAGE but production needs only PRIVATE; tests need PACKAGE"),
				rejected("public member used by a test in another package", TestOnlyPublicFixtures.PublicWidened.class,
						"$PublicWidened.used()> is PUBLIC but production needs only PACKAGE; tests need PUBLIC"),
				rejected("protected member used by a subclass in another package",
						TestOnlyPublicFixtures.ProtectedBase.class,
						"$ProtectedBase.used()> is PROTECTED but production needs only PACKAGE; "
								+ "tests need PROTECTED"),
				rejected("effective level comes from the enclosing class", TestOnlyPublicFixtures.Hidden.class,
						"$Hidden.used()> is PACKAGE but production needs only PRIVATE; tests need PACKAGE"),
				rejected("annotation no framework reads on a member", TestOnlyProductionFixtures.MarkedMember.class,
						"$MarkedMember.marked()> is used only by tests"),
				rejected("annotation no framework reads on a class", TestOnlyProductionFixtures.MarkedClass.class,
						"$MarkedClass.<init>(int)> is used only by tests"),
				rejected("helper of a Spring-annotated class", TestOnlyProductionFixtures.SpringAnnotatedClassWithHelper.class,
						"$SpringAnnotatedClassWithHelper.helper()> is used only by tests"),
				rejected("helper of a record", TestOnlyProductionFixtures.RecordWithHelper.class,
						"$RecordWithHelper.doubled()> is used only by tests"),
				rejected("extra constructor of a record", TestOnlyProductionFixtures.RecordWithExtraConstructor.class,
						"$RecordWithExtraConstructor.<init>(int)> is used only by tests"),
				rejected("static lookalike of a record accessor",
						TestOnlyProductionFixtures.RecordWithStaticLookalike.class,
						"$RecordWithStaticLookalike.second()> is used only by tests"),
				rejected("helper of an enum", TestOnlyProductionFixtures.ModeWithHelper.class,
						"$ModeWithHelper.label()> is used only by tests"),
				rejected("enum-typed field that is not a constant", TestOnlyProductionFixtures.ModeWithDefault.class,
						"$ModeWithDefault.DEFAULT> is PACKAGE but production needs only PRIVATE; tests need PACKAGE"),
				rejected("overload of an overridden method", TestOnlyProductionFixtures.TaskWithOverload.class,
						"$TaskWithOverload.run(int)> is used only by tests"),
				rejected("static method that hides a static method", TestOnlyProductionFixtures.StaticHider.class,
						"$StaticHider.make()> is used only by tests"),
				rejected("helper of configuration properties", TestOnlyProductionFixtures.PropertiesWithHelper.class,
						"$PropertiesWithHelper.compute()> is used only by tests"));
	}

	static Stream<Arguments> exemptFixtures() {
		return Stream.of(Arguments.of(Named.of("compliant members", TestOnlyProductionFixtures.Compliant.class)),
				Arguments.of(Named.of("members with framework annotations",
						TestOnlyProductionFixtures.FrameworkMembers.class)),
				Arguments
					.of(Named.of("record components and canonical constructor", TestOnlyProductionFixtures.Pair.class)),
				Arguments.of(Named.of("enum constants and values()", TestOnlyProductionFixtures.Mode.class)),
				Arguments.of(Named.of("override", TestOnlyProductionFixtures.Task.class)),
				Arguments.of(Named.of("constructor of a Spring-annotated class", TestOnlyProductionFixtures.SpringAnnotatedClass.class)),
				Arguments
					.of(Named.of("constructor of a runtime-hints registrar", TestOnlyProductionFixtures.Hints.class)),
				Arguments
					.of(Named.of("configuration property accessors", TestOnlyProductionFixtures.Properties.class)));
	}

	@ParameterizedTest
	@MethodSource("rejectedFixtures")
	void ruleRejectsViolatingFixture(Class<?> violator, String detail) {
		var result = rule().evaluate(only(violator));

		assertThat(result.hasViolation()).isTrue();
		assertThat(result.getFailureReport().getDetails()).singleElement().asString().contains(detail);
		assertThat(result.getFailureReport().toString())
			.contains("because production code must not exist only for tests");
	}

	@ParameterizedTest
	@MethodSource("exemptFixtures")
	void ruleExemptsMembersThatNeedNoProductionCaller(Class<?> exempt) {
		var result = rule().evaluate(only(exempt));

		assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
		assertThat(testCallers(exempt))
			.as("test classes that reach %s, without which the exemption proves nothing", exempt.getSimpleName())
			.isNotEmpty();
	}

	@Test
	void supportedApiExemptsItsPublicAndProtectedMembersOnly() {
		var result = rule(TestOnlyPublicFixtures.class.getName()::equals)
			.evaluate(only(TestOnlyPublicFixtures.Api.class));

		assertThat(result.hasViolation()).isTrue();
		assertThat(result.getFailureReport().getDetails()).singleElement()
			.asString()
			.contains("$Api.packageOnlyTests()> is used only by tests");
	}

	@Test
	void membersOfAnUnsupportedTypeAreNotExempt() {
		var result = rule(type -> false).evaluate(only(TestOnlyPublicFixtures.Api.class));

		assertThat(result.getFailureReport().getDetails()).hasSize(3)
			.anyMatch(detail -> detail.contains("$Api.publicOnlyTests()> is used only by tests"))
			.anyMatch(detail -> detail.contains("$Api.protectedOnlyTests()> is used only by tests"))
			.anyMatch(detail -> detail.contains("$Api.packageOnlyTests()> is used only by tests"));
	}

	@Test
	void aModuleWithoutSupportedApiRejectsEveryType() {
		assertThat(TestOnlyProductionCodeTests.SUPPORTED_API).as("the samples ship no supported API").isEmpty();
		assertThat(TestOnlyProductionCodeTests.UNSUPPORTED_EXAMPLES).isNotEmpty()
			.noneMatch(TestOnlyProductionCodeTests::isSupportedApiType);
		assertThat(TestOnlyProductionCodeTests.isSupportedApiType("de.subhransu.openrouter.springai.chat.OpenRouterChatModel"))
			.as("a type that the library module supports")
			.isFalse();
	}

	static Stream<Arguments> classFileLocations() {
		return Stream.of(
				Arguments.of(Named.of("Maven test output", "file:/work/repo/module/target/test-classes/a/B.class"),
						true),
				Arguments.of(Named.of("Gradle Java test output",
						"file:/C:/work/repo/module/build/classes/java/test/a/B.class"), true),
				Arguments.of(Named.of("Gradle Kotlin test output",
						"file:/work/repo/module/build/classes/kotlin/test/a/B.class"), true),
				Arguments.of(Named.of("Maven production output", "file:/work/repo/module/target/classes/a/B.class"),
						false),
				Arguments.of(Named.of("Gradle production output",
						"file:/C:/work/repo/module/build/classes/java/main/a/B.class"), false),
				Arguments.of(Named.of("packaged production jar", "jar:file:/home/user/.m2/lib-1.0.jar!/a/B.class"),
						false));
	}

	@ParameterizedTest
	@MethodSource("classFileLocations")
	void testClassesAreRecognizedByTheirOutputDirectory(String classFileUri, boolean isTest) {
		assertThat(TestOnlyProductionCodeTests.isTestOutput(classFileUri)).isEqualTo(isTest);
	}

	@Test
	void otherModulesNameEverySiblingModulePackageBelowTheRootPackage() throws IOException {
		Path root = TestOnlyProductionCodeTests.repositoryRoot();
		Path self = Path.of("").toAbsolutePath();
		String basePackage = "de.subhransu.openrouter.springai";
		Set<String> examined = new TreeSet<>();
		Set<String> expected = new TreeSet<>();
		try (Stream<Path> modules = Files.list(root)) {
			for (Path module : modules.filter(Files::isDirectory)
				.filter(directory -> directory.getFileName().toString().startsWith("openrouter-spring-ai"))
				.filter(directory -> !directory.equals(self))
				.toList()) {
				Path sources = module.resolve("src/main/java").resolve(basePackage.replace('.', '/'));
				if (Files.isDirectory(sources)) {
					try (Stream<Path> packages = Files.list(sources)) {
						packages.filter(Files::isDirectory)
							.filter(TestOnlyProductionCodeRuleTests::declaresTypes)
							.map(directory -> "." + directory.getFileName())
							.forEach(name -> {
								examined.add(name);
								if ((basePackage + name + ".").startsWith(TestOnlyProductionCodeTests.ROOT_PACKAGE)) {
									expected.add(name);
								}
							});
					}
				}
			}
		}

		assertThat(examined).as("packages of sibling modules, without which this check proves nothing").isNotEmpty();
		assertThat(TestOnlyProductionCodeTests.OTHER_MODULES)
			.as("packages of sibling modules that share this module's root package")
			.containsAll(expected);
	}

	/** A package with only {@code package-info.java} adds no classes to the classpath. */
	private static boolean declaresTypes(Path packageDirectory) {
		try (Stream<Path> files = Files.walk(packageDirectory)) {
			return files.anyMatch(file -> file.toString().endsWith(".java")
					&& !file.getFileName().toString().equals("package-info.java"));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static Arguments rejected(String name, Class<?> violator, String detail) {
		return Arguments.of(Named.of(name, violator), detail);
	}

	private static Set<String> testCallers(Class<?> fixture) {
		return FIXTURES.get(fixture)
			.getMembers()
			.stream()
			.flatMap(member -> member.getAccessesToSelf().stream())
			.map(access -> access.getOriginOwner().getName())
			.filter(name -> isTestCaller(name))
			.collect(Collectors.toSet());
	}

	private static boolean isTestCaller(String className) {
		return className.startsWith(TestOnlyProductionCodeRuleTests.class.getName())
				|| className.startsWith(TestOnlyOtherPackageCallers.class.getName());
	}

	private static ArchRule rule() {
		return TestOnlyProductionCodeTests.rule(type -> isTestCaller(type.getName()));
	}

	private static ArchRule rule(Predicate<String> isSupportedApiType) {
		return TestOnlyProductionCodeTests.rule(type -> isTestCaller(type.getName()), isSupportedApiType);
	}

	private static JavaClasses only(Class<?> type) {
		return FIXTURES.that(DescribedPredicate.describe("the selected fixture",
				(JavaClass candidate) -> candidate.getName().equals(type.getName())));
	}

	private static Class<?>[] fixtureTypes() {
		Set<Class<?>> types = Stream
			.of(TestOnlyProductionFixtures.class, TestOnlyPublicFixtures.class, TestOnlyProductionCallerFixtures.class,
					TestOnlyOtherPackageCallers.class, TestCallers.class)
			.flatMap(holder -> Stream.concat(Stream.of(holder), Arrays.stream(holder.getDeclaredClasses())))
			.collect(Collectors.toSet());
		types.add(TestOnlyProductionCodeRuleTests.class);
		return types.toArray(Class<?>[]::new);
	}

	/**
	 * The test caller of the fixtures: it reaches each fixture with package access, so
	 * test-only and widened members show up in the rule.
	 */
	@SuppressWarnings("unused")
	static final class TestCallers {

		private TestCallers() {
		}

		static int callViolations(TestOnlyProductionFixtures.TestOnly testOnly,
				TestOnlyProductionFixtures.TestOnlyField field, TestOnlyProductionFixtures.Widened widened,
				TestOnlyProductionFixtures.MarkedMember marked) {
			new TestOnlyProductionFixtures.TestOnlyConstructor(1);
			new TestOnlyProductionFixtures.MarkedClass(1);
			return testOnly.onlyTestsCallMe() + field.value + widened.used() + marked.marked();
		}

		static int callPackageLevel(TestOnlyPublicFixtures.Hidden hidden, TestOnlyPublicFixtures.Api api) {
			return hidden.used() + api.publicOnlyTests() + api.protectedOnlyTests() + api.packageOnlyTests();
		}

		static int callExemptions(TestOnlyProductionFixtures.Compliant compliant,
				TestOnlyProductionFixtures.FrameworkMembers members, TestOnlyProductionFixtures.Task task,
				TestOnlyProductionFixtures.Properties properties) {
			var pair = new TestOnlyProductionFixtures.Pair(1, 2);
			new TestOnlyProductionFixtures.SpringAnnotatedClass(1);
			new TestOnlyProductionFixtures.Hints();
			members.inject();
			task.run();
			properties.setName("synthetic");
			return compliant.shared() + members.property() + pair.first() + pair.second()
					+ TestOnlyProductionFixtures.Mode.values().length + TestOnlyProductionFixtures.Mode.FIRST.ordinal()
					+ properties.getName().length() + (properties.isEnabled() ? 1 : 0);
		}

		static int callTwins(TestOnlyProductionFixtures.SpringAnnotatedClassWithHelper component,
				TestOnlyProductionFixtures.RecordWithHelper recordFixture,
				TestOnlyProductionFixtures.ModeWithHelper mode, TestOnlyProductionFixtures.TaskWithOverload task,
				TestOnlyProductionFixtures.PropertiesWithHelper properties) {
			var extra = new TestOnlyProductionFixtures.RecordWithExtraConstructor(1);
			return component.helper() + recordFixture.doubled() + extra.first() + mode.label() + task.run(1)
					+ TestOnlyProductionFixtures.RecordWithStaticLookalike.second()
					+ TestOnlyProductionFixtures.ModeWithDefault.DEFAULT.ordinal()
					+ TestOnlyProductionFixtures.StaticHider.make() + properties.compute();
		}

	}

}
