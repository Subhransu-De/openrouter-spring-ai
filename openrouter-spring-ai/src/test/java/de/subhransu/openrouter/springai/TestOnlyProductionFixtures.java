package de.subhransu.openrouter.springai;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Lazy;

/**
 * Production-side fixtures for {@code TestOnlyProductionCodeRuleTests}. Each nested class
 * is reached from another top-level class, because access inside one top-level class
 * never needs more than {@code private}. The first group holds violations, the second
 * group holds the exempt members, and the third group pairs every exemption with a member
 * that the exemption must not cover.
 */
final class TestOnlyProductionFixtures {

	private TestOnlyProductionFixtures() {
	}

	/** A runtime annotation that no framework reads. */
	@Retention(RetentionPolicy.RUNTIME)
	@interface Marker {

	}

	// Violations

	/** Its only caller is the test class. */
	static class TestOnly {

		int onlyTestsCallMe() {
			return 1;
		}

	}

	/** Only the test class reads the field. */
	static class TestOnlyField {

		int value;

	}

	/** Only the test class calls the constructor. */
	static class TestOnlyConstructor {

		TestOnlyConstructor(int value) {
		}

	}

	/**
	 * Production reaches the method from inside the class; the test class needs package
	 * access.
	 */
	static class Widened {

		int used() {
			return 1;
		}

		int callsUsed() {
			return used();
		}

	}

	/** A runtime annotation that no framework reads does not make a member reachable. */
	static class MarkedMember {

		@Marker
		int marked() {
			return 1;
		}

	}

	/** The same holds for the constructor of a class with such an annotation. */
	@Marker
	static class MarkedClass {

		MarkedClass(int value) {
		}

	}

	// Exempt members

	/** Production and the test class reach the method with package access. */
	static class Compliant {

		int shared() {
			return 1;
		}

		int unused() {
			return internal();
		}

		private int internal() {
			return 1;
		}

	}

	/** A framework calls members that carry its annotations. */
	static class FrameworkMembers {

		@Autowired
		void inject() {
		}

		@JsonProperty
		int property() {
			return 1;
		}

	}

	/** The compiler generates the canonical constructor and the accessors. */
	record Pair(int first, int second) {

	}

	/** The compiler generates the constants and {@code values()}. */
	enum Mode {

		FIRST, SECOND

	}

	/** An override has the signature its supertype dictates. */
	static class Task implements Runnable {

		@Override
		public void run() {
		}

	}

	/**
	 * A class that carries a Spring annotation is instantiated by Spring. {@code @Lazy}
	 * is used because, unlike a stereotype, it is not picked up by the component scan of
	 * the samples application.
	 */
	@Lazy
	static class SpringAnnotatedClass {

		SpringAnnotatedClass(int value) {
		}

	}

	/** Spring creates a runtime-hints registrar by reflection. */
	static class Hints implements RuntimeHintsRegistrar {

		@Override
		public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		}

	}

	/** Boot binds configuration properties through their accessors. */
	@ConfigurationProperties("fixture")
	static class Properties {

		private String name = "";

		private boolean enabled;

		String getName() {
			return this.name;
		}

		void setName(String name) {
			this.name = name;
		}

		boolean isEnabled() {
			return this.enabled;
		}

	}

	// Members that an exemption must not cover

	/** Only the constructor of a Spring-annotated class is exempt. */
	@Lazy
	static class SpringAnnotatedClassWithHelper {

		int helper() {
			return 1;
		}

	}

	/** Only the accessors and the canonical constructor of a record are exempt. */
	record RecordWithHelper(int first) {

		int doubled() {
			return first * 2;
		}

	}

	/** A record's other constructor is not exempt. */
	record RecordWithExtraConstructor(int first, int second) {

		RecordWithExtraConstructor(int first) {
			this(first, 0);
		}

	}

	/** A static method that only resembles an accessor is not exempt. */
	record RecordWithStaticLookalike(int first) {

		static final int SECOND = 2;

		static int second() {
			return SECOND;
		}

	}

	/** Only the constants and {@code values()} of an enum are exempt. */
	enum ModeWithHelper {

		FIRST;

		int label() {
			return ordinal();
		}

	}

	/** A field that holds an enum value but is not a constant is not exempt. */
	enum ModeWithDefault {

		FIRST;

		static final ModeWithDefault DEFAULT = FIRST;

	}

	/** An overload is not an override. */
	static class TaskWithOverload implements Runnable {

		@Override
		public void run() {
		}

		int run(int times) {
			return times;
		}

	}

	/**
	 * A static method hides a static method of the superclass; it does not override it.
	 */
	static class StaticParent {

		static int make() {
			return 1;
		}

	}

	/** See {@link StaticParent}. */
	static class StaticHider extends StaticParent {

		static int make() {
			return 2;
		}

	}

	/** Only the accessors of configuration properties are exempt. */
	@ConfigurationProperties("fixture")
	static class PropertiesWithHelper {

		int compute() {
			return 1;
		}

	}

}
