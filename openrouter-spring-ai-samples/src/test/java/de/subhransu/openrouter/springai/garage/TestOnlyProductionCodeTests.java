package de.subhransu.openrouter.springai.garage;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.members;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fails the build when production code exists only for tests, or when a production
 * declaration is more visible than production callers need because a test uses it. The
 * import holds this module's production and test classes; a class is a test class when
 * its class file sits in a test output directory. {@code TestOnlyProductionCodeRuleTests}
 * proves the rule against fixtures. Each module keeps its own copy of this class with
 * identical logic; only {@code SUPPORTED_API} and the package constants differ.
 */
@AnalyzeClasses(packages = "de.subhransu.openrouter.springai.garage")
class TestOnlyProductionCodeTests {

	static final String ROOT_PACKAGE = "de.subhransu.openrouter.springai.garage.";

	/** Other modules' packages that can share this module's classpath. */
	static final List<String> OTHER_MODULES = List.of();

	/**
	 * Types whose public and protected members have callers outside this repository.
	 * Mirrors {@code .qodana/weaker-access.yaml} and {@code docs/api.md}.
	 */
	static final List<Pattern> SUPPORTED_API = List.of();

	/**
	 * Type names that {@code SUPPORTED_API} must accept; empty when the module has none.
	 */
	static final List<String> SUPPORTED_EXAMPLES = List.of();

	/** Type names that {@code SUPPORTED_API} must reject. */
	static final List<String> UNSUPPORTED_EXAMPLES = List.of("de.subhransu.openrouter.springai.garage.GarageRunService");

	/**
	 * Annotations of these frameworks make a member or class reachable by reflection. The
	 * nullness annotations of Spring and the JDK annotations such as {@code @Deprecated}
	 * do not.
	 */
	private static final List<String> FRAMEWORK_ANNOTATION_PREFIXES = List.of("org.springframework.",
			"com.fasterxml.jackson.", "jakarta.");

	private static final String NULLNESS_ANNOTATION_PREFIX = "org.springframework.lang.";

	/** Maven, Gradle and Gradle Kotlin test output directories. */
	private static final List<String> TEST_OUTPUT_DIRECTORIES = List.of("/test-classes/", "/classes/java/test/",
			"/classes/kotlin/test/");

	private enum Level {

		PRIVATE, PACKAGE, PROTECTED, PUBLIC

	}

	private TestOnlyProductionCodeTests() {
	}

	@ArchTest
	static final ArchRule production_code_exists_for_production = rule(TestOnlyProductionCodeTests::isTestClass);

	@ArchTest
	static void the_import_separates_production_from_test_classes(JavaClasses classes) {
		assertThat(classes).filteredOn(TestOnlyProductionCodeTests::isTestClass)
			.extracting(JavaClass::getName)
			.contains(TestOnlyProductionCodeTests.class.getName(), TestOnlyProductionCodeRuleTests.class.getName(),
					TestOnlyProductionFixtures.class.getName());
		assertThat(classes).filteredOn(type -> isProduction(type, TestOnlyProductionCodeTests::isTestClass))
			.as("production classes of this module")
			.isNotEmpty()
			.noneMatch(TestOnlyProductionCodeTests::isTestClass)
			.extracting(JavaClass::getName)
			.doesNotContain(TestOnlyProductionCodeTests.class.getName(), TestOnlyProductionFixtures.class.getName());
	}

	@ArchTest
	static void supported_api_mirrors_the_qodana_ignore_scopes(JavaClasses classes) throws IOException {
		List<Predicate<String>> scopes = qodanaIgnoreScopes(qodanaConfiguration());

		List<String> disagreements = classes.stream()
			.filter(type -> isProduction(type, TestOnlyProductionCodeTests::isTestClass))
			.filter(type -> type.getEnclosingClass().isEmpty() && !type.getSimpleName().isEmpty()
					&& Character.isUpperCase(type.getSimpleName().charAt(0)))
			.map(JavaClass::getName)
			.filter(name -> scopes.stream().anyMatch(scope -> scope.test(name)) != isSupportedApiType(name))
			.toList();

		assertThat(disagreements)
			.as("types where SUPPORTED_API and .qodana/weaker-access.yaml disagree about the supported API")
			.isEmpty();
	}

	static ArchRule rule(Predicate<JavaClass> isTest) {
		return rule(isTest, TestOnlyProductionCodeTests::isSupportedApiType);
	}

	static ArchRule rule(Predicate<JavaClass> isTest, Predicate<String> isSupportedApiType) {
		return members()
			.that(DescribedPredicate.describe("are declared in production classes",
					(JavaMember member) -> isProduction(member.getOwner(), isTest)))
			.should(notExistForTests(isTest, isSupportedApiType))
			.because("production code must not exist only for tests, and its access must not be widened for tests; "
					+ "reach behavior through the API that production callers use");
	}

	static boolean isTestClass(JavaClass type) {
		return type.getSource().map(source -> isTestOutput(source.getUri().toString())).orElse(false);
	}

	static boolean isTestOutput(String classFileUri) {
		return TEST_OUTPUT_DIRECTORIES.stream().anyMatch(classFileUri::contains);
	}

	static boolean isSupportedApiType(String topLevelTypeName) {
		return SUPPORTED_API.stream().anyMatch(pattern -> pattern.matcher(topLevelTypeName).matches());
	}

	/** Translates a Qodana {@code scope#src:} ignore entry into a test on a type name. */
	static Predicate<String> qodanaScope(String entry) {
		List<Predicate<String>> required = new ArrayList<>();
		List<Predicate<String>> excluded = new ArrayList<>();
		for (String term : entry.substring("scope#".length()).split("&&")) {
			boolean negated = term.startsWith("!");
			String glob = term.substring(negated ? "!src:".length() : "src:".length());
			// "..*" matches subpackages; a single "*" stays inside one package or name.
			Pattern pattern = Pattern.compile(
					glob.replace("..*", "\u0000").replace(".", "\\.").replace("*", "[^.]*").replace("\u0000", "\\..*"));
			(negated ? excluded : required).add(name -> pattern.matcher(name).matches());
		}
		return name -> required.stream().allMatch(term -> term.test(name))
				&& excluded.stream().noneMatch(term -> term.test(name));
	}

	static List<Predicate<String>> qodanaIgnoreScopes(String configuration) {
		Matcher entries = Pattern.compile("^\\s*-\\s*\"(scope#[^\"]+)\"", Pattern.MULTILINE).matcher(configuration);
		List<Predicate<String>> scopes = new ArrayList<>();
		while (entries.find()) {
			scopes.add(qodanaScope(entries.group(1)));
		}
		return scopes;
	}

	static Path repositoryRoot() {
		for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
			if (Files.exists(directory.resolve(".qodana/weaker-access.yaml"))) {
				return directory;
			}
		}
		throw new AssertionError(".qodana/weaker-access.yaml was not found in any parent of the working directory");
	}

	private static String qodanaConfiguration() throws IOException {
		return Files.readString(repositoryRoot().resolve(".qodana/weaker-access.yaml"));
	}

	private static boolean isProduction(JavaClass type, Predicate<JavaClass> isTest) {
		return type.getName().startsWith(ROOT_PACKAGE) && !isTest.test(type)
				&& OTHER_MODULES.stream().noneMatch(module -> type.getPackageName().contains(module))
				&& !type.getModifiers().contains(JavaModifier.SYNTHETIC);
	}

	private static ArchCondition<JavaMember> notExistForTests(Predicate<JavaClass> isTest,
			Predicate<String> isSupportedApiType) {
		return new ArchCondition<>("not exist for tests") {

			@Override
			public void check(JavaMember member, ConditionEvents events) {
				String problem = problem(member, isTest, isSupportedApiType);
				if (problem != null) {
					events.add(SimpleConditionEvent.violated(member, member.getDescription() + " " + problem));
				}
			}
		};
	}

	private static String problem(JavaMember member, Predicate<JavaClass> isTest,
			Predicate<String> isSupportedApiType) {
		JavaClass owner = member.getOwner();
		Level declared = effectiveLevel(owner, member);
		if (declared == Level.PRIVATE || isFrameworkOrBoilerplate(owner, member)) {
			return null;
		}
		Level production = null;
		Level tests = null;
		for (JavaAccess<?> access : member.getAccessesToSelf()) {
			JavaClass origin = access.getOriginOwner();
			Level needed = neededLevel(owner, origin);
			if (isTest.test(origin)) {
				tests = widest(tests, needed);
			}
			else {
				production = widest(production, needed);
			}
		}
		if (tests == null || isSupportedApi(owner, declared, isSupportedApiType)) {
			return null;
		}
		if (production == null) {
			return "is used only by tests";
		}
		if (declared.compareTo(production) > 0 && tests.compareTo(production) > 0) {
			return "is " + declared + " but production needs only " + production + "; tests need " + tests;
		}
		return null;
	}

	private static boolean isSupportedApi(JavaClass owner, Level declared, Predicate<String> isSupportedApiType) {
		return declared.compareTo(Level.PROTECTED) >= 0 && isSupportedApiType.test(topLevel(owner).getName());
	}

	/**
	 * Whether a framework reaches the element by reflection: a Spring, Jackson or Jakarta
	 * annotation, but not a nullness marker.
	 */
	private static boolean hasFrameworkAnnotation(HasAnnotations<?> element) {
		return element.getAnnotations()
			.stream()
			.map(annotation -> annotation.getRawType().getName())
			.anyMatch(name -> !name.startsWith(NULLNESS_ANNOTATION_PREFIX)
					&& FRAMEWORK_ANNOTATION_PREFIXES.stream().anyMatch(name::startsWith));
	}

	/**
	 * Members that a framework or the language reaches without a call site, or whose
	 * access another declaration dictates.
	 */
	private static boolean isFrameworkOrBoilerplate(JavaClass owner, JavaMember member) {
		String name = member.getName();
		if (member.getModifiers().contains(JavaModifier.SYNTHETIC)
				|| member.getModifiers().contains(JavaModifier.BRIDGE) || name.startsWith("lambda$")
				|| "<clinit>".equals(name) || hasFrameworkAnnotation(member)) {
			return true;
		}
		if (member instanceof JavaMethod method && isOverride(owner, method)) {
			return true;
		}
		if (owner.isEnum() && (member instanceof JavaMethod && Set.of("values", "valueOf", "$values").contains(name)
				|| member instanceof JavaField field && field.getModifiers().contains(JavaModifier.ENUM))) {
			return true;
		}
		if (owner.isRecord() && isRecordCanonicalMember(owner, member)) {
			return true;
		}
		// Spring instantiates annotated components and registered hint classes by
		// reflection.
		if ("<init>".equals(name) && (hasFrameworkAnnotation(owner)
				|| owner.isAssignableTo("org.springframework.aot.hint.RuntimeHintsRegistrar"))) {
			return true;
		}
		// Boot binds configuration properties through their accessors.
		return owner.isAnnotatedWith("org.springframework.boot.context.properties.ConfigurationProperties")
				&& (name.startsWith("set") || name.startsWith("get") || name.startsWith("is"));
	}

	private static boolean isRecordCanonicalMember(JavaClass owner, JavaMember member) {
		long components = owner.getFields()
			.stream()
			.filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
			.count();
		if (member instanceof JavaConstructor constructor) {
			return constructor.getRawParameterTypes().size() == components;
		}
		return member instanceof JavaMethod method && method.getRawParameterTypes().isEmpty()
				&& !method.getModifiers().contains(JavaModifier.STATIC)
				&& owner.getFields()
					.stream()
					.anyMatch(field -> !field.getModifiers().contains(JavaModifier.STATIC)
							&& field.getName().equals(method.getName()));
	}

	private static boolean isOverride(JavaClass owner, JavaMethod method) {
		if (method.getModifiers().contains(JavaModifier.STATIC)) {
			return false;
		}
		List<String> parameters = method.getRawParameterTypes().stream().map(JavaClass::getName).toList();
		return Stream.concat(owner.getAllRawSuperclasses().stream(), owner.getAllRawInterfaces().stream())
			.flatMap(type -> type.getMethods().stream())
			.anyMatch(candidate -> candidate.getName().equals(method.getName())
					&& parameters.equals(candidate.getRawParameterTypes().stream().map(JavaClass::getName).toList()));
	}

	private static Level neededLevel(JavaClass owner, JavaClass origin) {
		if (topLevel(owner).equals(topLevel(origin))) {
			return Level.PRIVATE;
		}
		if (owner.getPackageName().equals(origin.getPackageName())) {
			return Level.PACKAGE;
		}
		return origin.isAssignableTo(owner.getName()) ? Level.PROTECTED : Level.PUBLIC;
	}

	/**
	 * The access a caller really gets: the narrowest of the member and its enclosing
	 * classes.
	 */
	private static Level effectiveLevel(JavaClass owner, JavaMember member) {
		Level level = level(member.getModifiers());
		for (JavaClass type = owner; type != null; type = type.getEnclosingClass().orElse(null)) {
			Level typeLevel = level(type.getModifiers());
			level = typeLevel.compareTo(level) < 0 ? typeLevel : level;
		}
		return level;
	}

	private static Level level(Set<JavaModifier> modifiers) {
		if (modifiers.contains(JavaModifier.PUBLIC)) {
			return Level.PUBLIC;
		}
		if (modifiers.contains(JavaModifier.PROTECTED)) {
			return Level.PROTECTED;
		}
		return modifiers.contains(JavaModifier.PRIVATE) ? Level.PRIVATE : Level.PACKAGE;
	}

	private static Level widest(Level current, Level candidate) {
		return current == null || candidate.compareTo(current) > 0 ? candidate : current;
	}

	private static JavaClass topLevel(JavaClass type) {
		JavaClass top = type;
		while (top.getEnclosingClass().isPresent()) {
			top = top.getEnclosingClass().orElseThrow();
		}
		return top;
	}

}
