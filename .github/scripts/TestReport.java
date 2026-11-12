import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Builds the two pull request comments about tests: test counts per area and test time changes.
 *
 * <ul>
 * <li>{@code collect} reads the JUnit Open Test Reporting XML of one build (falling back to Surefire XML, which has
 * no tags) and writes one small tab-separated report.</li>
 * <li>{@code diff} compares the pull request's reports with the {@code main} reports and writes
 * {@code counts.md} and {@code timing.md}.</li>
 * <li>{@code selftest} checks the parsers and the comparison rules against built-in examples.</li>
 * </ul>
 *
 * Run with {@code java .github/scripts/TestReport.java <command> ...}. Reports from pull requests are untrusted data:
 * every name that reaches Markdown is reduced to a strict character set.
 */
public final class TestReport {

	private static final String UNTAGGED = "untagged";

	private static final Pattern SAFE_ID = Pattern.compile("[^A-Za-z0-9_$.#\\[\\]-]");

	private static final Pattern SAFE_AREA = Pattern.compile("[^a-z0-9.-]");

	private static final Pattern SAFE_SHA = Pattern.compile("[0-9a-f]{7,40}");

	/** Run-to-run noise rules; see the {@code diff} options for where the numbers come from. */
	record Rules(double minMs, double minFactor, double areaWarnMs, double areaFailMs, double areaFailShare,
			double classShare, double newSlowMs) {
	}

	record Test(String id, String area, int runs, double ms) {
	}

	/** {@code tagged}: the report has tag data, so an empty area means the test really has no tag. */
	record Report(String java, String commit, Map<String, Test> tests, Map<String, Double> classes, int order,
			boolean tagged) {

		double totalMs() {
			return this.tests.values().stream().mapToDouble(Test::ms).sum();
		}

	}

	private TestReport() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			throw new IllegalArgumentException("usage: TestReport collect|diff|selftest [options]");
		}
		Map<String, String> options = new HashMap<>();
		List<String> paths = new ArrayList<>();
		for (int i = 1; i < args.length; i++) {
			if (args[i].startsWith("--")) {
				options.put(args[i].substring(2), args[++i]);
			}
			else {
				paths.add(args[i]);
			}
		}
		switch (args[0]) {
			case "collect" -> collect(options, paths);
			case "diff" -> diff(options);
			case "selftest" -> selftest();
			default -> throw new IllegalArgumentException("unknown command " + args[0]);
		}
	}

	// ---------------------------------------------------------------- collect

	private static void collect(Map<String, String> options, List<String> roots) throws IOException {
		List<Path> otr = new ArrayList<>();
		List<Path> surefire = new ArrayList<>();
		for (String root : roots) {
			// An unmatched shell glob arrives as a literal path; a build that stopped early has no reports.
			if (root.contains("*") || !Files.isDirectory(Path.of(root))) {
				continue;
			}
			try (Stream<Path> files = Files.walk(Path.of(root))) {
				files.filter(Files::isRegularFile).forEach(file -> {
					String name = file.getFileName().toString();
					String where = file.toString().replace('\\', '/');
					if (where.contains("/junit-reports/") && name.startsWith("open-test-report") && name.endsWith(".xml")) {
						otr.add(file);
					}
					else if (where.contains("/surefire-reports/") && name.startsWith("TEST-") && name.endsWith(".xml")) {
						surefire.add(file);
					}
				});
			}
		}
		Map<String, Test> tagged = new TreeMap<>();
		Map<String, Double> taggedClasses = new TreeMap<>();
		for (Path file : otr) {
			try (InputStream in = Files.newInputStream(file)) {
				readOpenTestReport(in, tagged, taggedClasses);
			}
		}
		Map<String, Test> timed = new TreeMap<>();
		Map<String, Double> timedClasses = new TreeMap<>();
		for (Path file : surefire) {
			try (InputStream in = Files.newInputStream(file)) {
				readSurefire(in, timed, timedClasses);
			}
		}
		// Times always come from Surefire when it is there: a main run without Open Test Reporting is then
		// comparable. Open Test Reporting adds the tags (its own times run a few percent higher).
		Map<String, Test> tests = new TreeMap<>();
		if (timed.isEmpty()) {
			tests.putAll(tagged);
		}
		else {
			timed.forEach((id, t) -> tests.put(id,
					new Test(id, tagged.containsKey(id) ? tagged.get(id).area() : "", t.runs(), t.ms())));
		}
		Map<String, Double> classes = timed.isEmpty() ? taggedClasses : timedClasses;
		String source = (otr.isEmpty() ? "" : "tags:open-test-reporting ")
				+ (timed.isEmpty() ? "times:open-test-reporting" : "times:surefire");
		Report report = new Report(require(options, "java"), options.getOrDefault("commit", ""), tests, classes, 0,
				!otr.isEmpty());
		Path out = Path.of(require(options, "out"));
		if (out.getParent() != null) {
			Files.createDirectories(out.getParent());
		}
		Files.writeString(out, write(report, source), StandardCharsets.UTF_8);
		System.out.printf(Locale.ROOT, "%s: %d tests, %d classes (%s; %d + %d files)%n", out, tests.size(),
				classes.size(), source, otr.size(), surefire.size());
	}

	private static XMLStreamReader xml(InputStream in) throws XMLStreamException {
		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		return factory.createXMLStreamReader(in);
	}

	private static final class Node {

		String parent;

		String type = "";

		String cls;

		String method;

		final List<String> tags = new ArrayList<>();

		Instant start;

		Instant end;

	}

	/** Reads the events format of Open Test Reporting: {@code started}, {@code finished} and {@code reported}. */
	static void readOpenTestReport(InputStream in, Map<String, Test> tests, Map<String, Double> classes) {
		Map<String, Node> nodes = new LinkedHashMap<>();
		try {
			XMLStreamReader r = xml(in);
			Node current = null;
			String text = null;
			while (r.hasNext()) {
				int event = r.next();
				if (event == XMLStreamConstants.START_ELEMENT) {
					String name = r.getLocalName();
					switch (name) {
						case "started", "reported" -> {
							current = new Node();
							current.parent = r.getAttributeValue(null, "parentId");
							current.start = Instant.parse(r.getAttributeValue(null, "time"));
							if (name.equals("reported")) {
								current.end = current.start;
							}
							nodes.put(r.getAttributeValue(null, "id"), current);
						}
						case "finished" -> {
							Node node = nodes.get(r.getAttributeValue(null, "id"));
							if (node != null) {
								node.end = Instant.parse(r.getAttributeValue(null, "time"));
							}
							current = null;
						}
						case "methodSource", "fieldSource" -> {
							if (current != null) {
								current.cls = r.getAttributeValue(null, "className");
								current.method = r.getAttributeValue(null,
										name.equals("methodSource") ? "methodName" : "fieldName");
							}
						}
						case "classSource" -> {
							if (current != null && current.cls == null) {
								current.cls = r.getAttributeValue(null, "className");
							}
						}
						default -> {
						}
					}
					text = null;
				}
				else if (event == XMLStreamConstants.CHARACTERS) {
					text = (text == null ? "" : text) + r.getText();
				}
				else if (event == XMLStreamConstants.END_ELEMENT && current != null && text != null) {
					switch (r.getLocalName()) {
						case "tag" -> current.tags.add(text.trim());
						case "type" -> current.type = text.trim();
						case "uniqueId" -> {
							// ArchUnit rule fields: [class:...]/[field:...] when no field source is reported.
							var m = Pattern.compile("\\[class:([^\\]]+)\\]/\\[field:([^\\]]+)\\]").matcher(text);
							if (m.find() && current.method == null) {
								current.cls = m.group(1);
								current.method = m.group(2);
							}
						}
						default -> {
						}
					}
					text = null;
				}
			}
		}
		catch (XMLStreamException ex) {
			throw new IllegalStateException("cannot read Open Test Reporting XML", ex);
		}
		for (Node node : nodes.values()) {
			if (!node.type.equals("TEST") || node.end == null) {
				continue;
			}
			String cls = node.cls;
			String method = node.method;
			Node parent = node.parent == null ? null : nodes.get(node.parent);
			if (method == null && parent != null && parent.method != null) {
				cls = parent.cls;
				method = parent.method;
			}
			if (cls == null || method == null) {
				continue;
			}
			add(tests, cls + "#" + method, area(node.tags), millis(node));
		}
		for (Node node : nodes.values()) {
			Node parent = node.parent == null ? null : nodes.get(node.parent);
			boolean topLevelClass = node.type.equals("CONTAINER") && node.cls != null && node.method == null
					&& node.end != null && (parent == null || parent.cls == null);
			if (topLevelClass) {
				classes.merge(node.cls, millis(node), Double::sum);
			}
		}
	}

	private static double millis(Node node) {
		return Duration.between(node.start, node.end).toNanos() / 1_000_000.0;
	}

	/** Reads Surefire's XML. It has no tags, so the area stays empty and {@code diff} fills it in. */
	static void readSurefire(InputStream in, Map<String, Test> tests, Map<String, Double> classes) {
		try {
			XMLStreamReader r = xml(in);
			String suite = null;
			while (r.hasNext()) {
				if (r.next() != XMLStreamConstants.START_ELEMENT) {
					continue;
				}
				if (r.getLocalName().equals("testsuite")) {
					suite = r.getAttributeValue(null, "name");
					String time = r.getAttributeValue(null, "time");
					if (suite != null && time != null) {
						classes.merge(suite, seconds(time), Double::sum);
					}
				}
				else if (r.getLocalName().equals("testcase")) {
					String name = r.getAttributeValue(null, "name");
					String cls = r.getAttributeValue(null, "classname");
					if (name == null || cls == null) {
						continue;
					}
					// ArchUnit rules carry only the simple class name; the suite has the full one.
					if (!cls.contains(".") && suite != null && (suite.endsWith("." + cls) || suite.endsWith("$" + cls))) {
						cls = suite;
					}
					String method = name.split("[(\\[ ]", 2)[0];
					add(tests, cls + "#" + method, "", seconds(r.getAttributeValue(null, "time")));
				}
			}
		}
		catch (XMLStreamException ex) {
			throw new IllegalStateException("cannot read Surefire XML", ex);
		}
	}

	private static double seconds(String value) {
		return value == null || value.isBlank() ? 0 : Double.parseDouble(value.replace(",", "")) * 1000;
	}

	private static void add(Map<String, Test> tests, String id, String area, double ms) {
		tests.merge(id, new Test(id, area, 1, ms),
				(a, b) -> new Test(id, a.area().isEmpty() ? b.area() : a.area(), a.runs() + b.runs(), a.ms() + b.ms()));
	}

	private static String simple(String className) {
		return className.substring(className.lastIndexOf('.') + 1).replace('$', '.');
	}

	/** {@code de.x.Outer$InnerTests#method} is shown as {@code Outer.InnerTests#method}. */
	static String display(String id) {
		int hash = id.indexOf('#');
		return hash < 0 ? simple(id) : simple(id.substring(0, hash)) + id.substring(hash);
	}

	/** The area is the parent tag: the tag without a dot ({@code api}), or the part before the dot of a leaf tag. */
	static String area(List<String> tags) {
		return tags.stream()
			.filter(tag -> !tag.contains("."))
			.findFirst()
			.or(() -> tags.stream().map(tag -> tag.substring(0, tag.indexOf('.'))).findFirst())
			.orElse(UNTAGGED);
	}

	static String write(Report report, String source) {
		StringBuilder out = new StringBuilder("#test-report\t1\n");
		out.append("java\t").append(report.java()).append('\n');
		out.append("commit\t").append(report.commit()).append('\n');
		out.append("source\t").append(source).append('\n');
		report.tests()
			.values()
			.forEach(t -> out.append(String.format(Locale.ROOT, "test\t%s\t%d\t%.1f\t%s%n", t.area(), t.runs(), t.ms(),
					t.id())));
		report.classes().forEach((cls, ms) -> out.append(String.format(Locale.ROOT, "class\t%.1f\t%s%n", ms, cls)));
		return out.toString();
	}

	static Report read(String text, int order) {
		String java = "";
		String commit = "";
		boolean tagged = false;
		Map<String, Test> tests = new LinkedHashMap<>();
		Map<String, Double> classes = new LinkedHashMap<>();
		for (String line : text.split("\r?\n")) {
			String[] c = line.split("\t");
			switch (c[0]) {
				case "java" -> java = c.length > 1 ? c[1].replaceAll("[^0-9]", "") : "";
				case "commit" -> commit = c.length > 1 && SAFE_SHA.matcher(c[1]).matches() ? c[1] : "";
				case "source" -> tagged = c.length > 1 && c[1].contains("tags:");
				case "test" -> {
					if (c.length == 5) {
						String id = SAFE_ID.matcher(c[4]).replaceAll("?");
						tests.put(id, new Test(id, SAFE_AREA.matcher(c[1]).replaceAll(""), Integer.parseInt(c[2]),
								Double.parseDouble(c[3])));
					}
				}
				case "class" -> {
					if (c.length == 3) {
						classes.put(SAFE_ID.matcher(c[2]).replaceAll("?"), Double.parseDouble(c[1]));
					}
				}
				default -> {
				}
			}
		}
		return new Report(java, commit, tests, classes, order, tagged);
	}

	// ---------------------------------------------------------------- diff

	/**
	 * Options: {@code --head DIR} (this pull request's reports, {@code head.tsv}, plus a {@code base.tsv} when the
	 * pull request also tested its base commit), {@code --base DIR} (one numbered folder per {@code main} run, newest
	 * first), {@code --base-commit SHA}, {@code --head-commit SHA}, {@code --pr N}, {@code --java 25,21,17},
	 * {@code --tables-from 25} and {@code --out DIR}.
	 */
	private static void diff(Map<String, String> options) throws IOException {
		List<Report> heads = new ArrayList<>();
		List<Report> bases = new ArrayList<>();
		for (Path file : reports(Path.of(require(options, "head")))) {
			Report report = read(Files.readString(file), 0);
			(file.getFileName().toString().equals("base.tsv") ? bases : heads).add(report);
		}
		Path baseDir = Path.of(options.getOrDefault("base", "base"));
		if (Files.isDirectory(baseDir)) {
			for (Path file : reports(baseDir)) {
				if (file.getFileName().toString().equals("head.tsv")) {
					bases.add(read(Files.readString(file), order(baseDir, file)));
				}
			}
		}
		String pr = require(options, "pr").replaceAll("[^0-9]", "");
		List<String> javas = Arrays.asList(options.getOrDefault("java", "25,21,17").split(","));
		Comparison comparison = new Comparison(heads, bases, options.getOrDefault("base-commit", ""),
				options.getOrDefault("tables-from", "25"), rules(options));
		Path out = Path.of(options.getOrDefault("out", "comments"));
		Files.createDirectories(out);
		String head = options.getOrDefault("head-commit", "");
		Files.writeString(out.resolve("counts.md"), comparison.counts(marker("counts", head)));
		Files.writeString(out.resolve("timing.md"), comparison.timing(marker("timing", head), javas, "#" + pr));
	}

	private static Rules rules(Map<String, String> o) {
		// Measured on this repository's CI from main runs of the same commit; CONTRIBUTING.md has the numbers.
		return new Rules(number(o, "min-ms", 3000), number(o, "min-factor", 2), number(o, "area-warn-ms", 5000),
				number(o, "area-fail-ms", 10000), number(o, "area-fail-share", 0.35), 0.5, 1000);
	}

	private static double number(Map<String, String> options, String key, double fallback) {
		return options.containsKey(key) ? Double.parseDouble(options.get(key)) : fallback;
	}

	private static String marker(String kind, String commit) {
		String sha = SAFE_SHA.matcher(commit).matches() ? commit : "unknown";
		return "<!-- test-report:" + kind + ":" + sha + " -->";
	}

	private static List<Path> reports(Path dir) throws IOException {
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(f -> f.getFileName().toString().endsWith(".tsv")).sorted().toList();
		}
	}

	private static int order(Path baseDir, Path file) {
		String first = baseDir.relativize(file).getName(0).toString().replaceAll("[^0-9]", "");
		return first.isEmpty() ? 99 : Integer.parseInt(first);
	}

	static final class Comparison {

		private static final List<String> PARENTS = List.of("api", "http", "configuration", "samples", "tools",
				"streaming", "multimodal", "observability", "embeddings", "build-release");

		private final List<Report> heads;

		private final List<Report> bases;

		private final String baseCommit;

		private final String tablesFrom;

		private final Rules rules;

		/** Area of every test id, from the tagged reports; untagged base reports borrow it by test id. */
		private final Map<String, String> areas = new HashMap<>();

		Comparison(List<Report> heads, List<Report> bases, String baseCommit, String tablesFrom, Rules rules) {
			this.heads = heads;
			this.bases = bases;
			this.baseCommit = baseCommit;
			this.tablesFrom = heads.stream().anyMatch(r -> r.java().equals(tablesFrom)) || heads.isEmpty() ? tablesFrom
					: heads.get(0).java();
			this.rules = rules;
			Stream.concat(bases.stream(), heads.stream())
				.filter(Report::tagged)
				.flatMap(r -> r.tests().values().stream())
				.filter(t -> !t.area().isEmpty() && !t.area().equals(UNTAGGED))
				.forEach(t -> this.areas.put(t.id(), t.area()));
		}

		private String areaOf(Report report, Test test) {
			if (report.tagged()) {
				return test.area().isEmpty() ? UNTAGGED : test.area();
			}
			return this.areas.getOrDefault(test.id(), UNTAGGED);
		}

		private Report head(String java) {
			return this.heads.stream().filter(r -> r.java().equals(java)).findFirst().orElse(null);
		}

		private List<Report> bases(String java) {
			return this.bases.stream()
				.filter(r -> r.java().equals(java))
				.sorted(Comparator.comparingInt(Report::order))
				.limit(5)
				.toList();
		}

		/**
		 * Counts compare only with the exact {@code main} commit the pull request was tested against. Another
		 * commit would show tests added to {@code main} meanwhile as removed here, so without it the table shows
		 * this pull request's numbers alone.
		 */
		private Report countBase() {
			return this.bases.stream()
				.filter(r -> r.java().equals(this.tablesFrom))
				.filter(r -> !this.baseCommit.isEmpty() && r.commit().equals(this.baseCommit))
				.min(Comparator.comparingInt(Report::order))
				.orElse(null);
		}

		private List<String> areaOrder(Stream<String> present) {
			Set<String> seen = present.collect(Collectors.toCollection(LinkedHashSet::new));
			List<String> order = new ArrayList<>(PARENTS.stream().filter(seen::contains).toList());
			seen.stream().filter(a -> !order.contains(a)).sorted().forEach(order::add);
			return order;
		}

		String counts(String marker) {
			Report head = head(this.tablesFrom);
			StringBuilder md = new StringBuilder(marker).append("\n### Test area\n\n");
			if (head == null) {
				return md.append("No test report.\n").toString();
			}
			Report base = countBase();
			Map<String, Test> before = base == null ? Map.of() : base.tests();
			List<String> areaList = areaOrder(Stream.concat(head.tests().values().stream().map(t -> areaOf(head, t)),
					before.values().stream().map(t -> areaOf(base, t))));
			record Row(String mark, String area, int tb, int tn, int rb, int rn, int added, int removed, int order) {
			}
			List<Row> rows = new ArrayList<>();
			for (String area : areaList) {
				List<Test> b = before.values().stream().filter(t -> areaOf(base, t).equals(area)).toList();
				List<Test> n = head.tests().values().stream().filter(t -> areaOf(head, t).equals(area)).toList();
				int added = (int) n.stream().filter(t -> !before.containsKey(t.id())).count();
				int removed = (int) b.stream().filter(t -> !head.tests().containsKey(t.id())).count();
				int loss = b.size() - n.size();
				String mark = area.equals(UNTAGGED) && !n.isEmpty() ? "❌"
						: (!b.isEmpty() && n.isEmpty()) || (!b.isEmpty() && loss >= 0.1 * b.size() && loss > 0) ? "❌"
								: loss > 0 ? "⚠️" : "✅";
				rows.add(new Row(mark, area, b.size(), n.size(), runs(b), runs(n), added, removed, rows.size()));
			}
			rows.sort(Comparator.comparingInt((Row r) -> rank(r.mark())).thenComparingInt(Row::order));
			md.append("| | Area | Tests | Δ | Runs | Δ | Added | Removed |\n");
			md.append("| :-: | --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
			for (Row r : rows) {
				md.append(String.format(Locale.ROOT, "| %s | `%s` | %s | %s | %s | %s | %d | %d |%n", r.mark(), r.area(),
						arrow(base, r.tb(), r.tn()), delta(base, r.tb(), r.tn()), arrow(base, r.rb(), r.rn()),
						base == null ? "" : signed(r.rn() - r.rb()), r.added(), r.removed()));
			}
			int tb = before.size();
			int tn = head.tests().size();
			int rb = runs(before.values());
			int rn = runs(head.tests().values());
			md.append(String.format(Locale.ROOT, "| | **Total** | **%s** | **%s** | **%s** | **%s** | **%d** | **%d** |%n",
					arrow(base, tb, tn), delta(base, tb, tn), arrow(base, rb, rn), base == null ? "" : signed(rn - rb),
					rows.stream().mapToInt(Row::added).sum(), rows.stream().mapToInt(Row::removed).sum()));
			return md.toString();
		}

		String timing(String marker, List<String> javas, String pr) {
			StringBuilder md = new StringBuilder(marker).append("\n### Test time changes\n\n");
			md.append(chart(javas, pr)).append('\n');
			Report head = head(this.tablesFrom);
			List<Report> base = bases(this.tablesFrom);
			if (head == null || base.isEmpty()) {
				return md.toString();
			}
			String java = "Java " + this.tablesFrom;
			// Areas: compared with the slowest main run, so ordinary run-to-run noise stays green.
			List<String> areaList = areaOrder(head.tests().values().stream().map(t -> areaOf(head, t)));
			record AreaRow(String mark, String area, double main, double pr, int order) {
			}
			List<AreaRow> areaRows = new ArrayList<>();
			for (String area : areaList) {
				double now = areaMs(head, area);
				double[] runs = base.stream().mapToDouble(r -> areaMs(r, area)).toArray();
				double top = Arrays.stream(runs).max().orElse(0);
				double over = now - top;
				String mark = over >= this.rules.areaFailMs() && over >= this.rules.areaFailShare() * top ? "❌"
						: over >= this.rules.areaWarnMs() ? "⚠️" : "✅";
				areaRows.add(new AreaRow(mark, area, median(runs), now, areaRows.size()));
			}
			areaRows.sort(Comparator.comparingInt((AreaRow r) -> rank(r.mark())).thenComparingInt(AreaRow::order));
			md.append("#### Areas · ").append(java).append("\n\n| | Area | main | PR | Δ |\n| :-: | --- | ---: | ---: | ---: |\n");
			for (AreaRow r : areaRows) {
				md.append(String.format(Locale.ROOT, "| %s | `%s` | %s | %s | %s |%n", r.mark(), r.area(), time(r.main()),
						time(r.pr()), signedTime(r.pr() - r.main())));
			}
			// Tests: changed against every main run, in the same direction.
			record Changed(String mark, Test test, double main, double classMain, double classNow) {
			}
			List<Changed> changed = new ArrayList<>();
			Map<String, Double> addedPerClass = new HashMap<>();
			for (Test test : head.tests().values()) {
				double[] runs = base.stream()
					.map(r -> r.tests().get(test.id()))
					.filter(Objects::nonNull)
					.mapToDouble(Test::ms)
					.toArray();
				if (runs.length == 0 || !changedEverywhere(test.ms(), runs)) {
					continue;
				}
				String cls = test.id().substring(0, test.id().indexOf('#'));
				double main = median(runs);
				double classMain = median(base.stream()
					.map(r -> r.classes().get(cls))
					.filter(Objects::nonNull)
					.mapToDouble(Double::doubleValue)
					.toArray());
				double classNow = head.classes().getOrDefault(cls, 0.0);
				if (test.ms() > main) {
					addedPerClass.merge(cls, test.ms() - main, Double::sum);
				}
				changed.add(new Changed("", test, main, classMain, classNow));
			}
			List<Changed> slower = new ArrayList<>();
			List<Changed> faster = new ArrayList<>();
			for (Changed c : changed) {
				if (c.test().ms() > c.main()) {
					String cls = c.test().id().substring(0, c.test().id().indexOf('#'));
					double grew = c.classNow() - c.classMain();
					// A real cost when the class grew by at least half of what its slower tests added; otherwise
					// start-up work (a Spring context, an HTTP client) probably moved into this test.
					boolean real = grew >= this.rules.minMs() && grew >= this.rules.classShare() * addedPerClass.get(cls);
					slower.add(new Changed(real ? "❌" : "⚠️", c.test(), c.main(), c.classMain(), c.classNow()));
				}
				else {
					faster.add(new Changed("✅", c.test(), c.main(), c.classMain(), c.classNow()));
				}
			}
			Comparator<Changed> bySize = Comparator.comparingDouble(c -> -Math.abs(c.test().ms() - c.main()));
			slower.sort(Comparator.comparingInt((Changed c) -> rank(c.mark())).thenComparing(bySize));
			faster.sort(bySize);
			for (var section : List.of(Map.entry("slower", slower), Map.entry("faster", faster))) {
				List<Changed> list = section.getValue();
				if (list.isEmpty()) {
					continue;
				}
				md.append(String.format(Locale.ROOT, "%n#### %d %s %s · %s%n%n", list.size(),
						list.size() == 1 ? "test" : "tests", section.getKey(), java));
				md.append("| | Test | Area | main | PR | Δ | Class time |\n| :-: | --- | --- | ---: | ---: | ---: | ---: |\n");
				list.stream()
					.limit(30)
					.forEach(c -> md.append(String.format(Locale.ROOT, "| %s | `%s` | `%s` | %s | %s | %s (%s) | %s → %s |%n",
							c.mark(), display(c.test().id()), areaOf(head, c.test()), time(c.main()), time(c.test().ms()),
							signedTime(c.test().ms() - c.main()), factor(c.main(), c.test().ms()), time(c.classMain()),
							time(c.classNow()))));
				if (list.size() > 30) {
					md.append(String.format(Locale.ROOT, "| | … and %d more | | | | | |%n", list.size() - 30));
				}
			}
			Set<String> known = base.stream()
				.flatMap(r -> r.tests().keySet().stream())
				.collect(Collectors.toSet());
			List<Test> newSlow = head.tests()
				.values()
				.stream()
				.filter(t -> !known.contains(t.id()) && t.ms() >= this.rules.newSlowMs())
				.sorted(Comparator.comparingDouble(t -> -t.ms()))
				.toList();
			if (!newSlow.isEmpty()) {
				md.append(String.format(Locale.ROOT,
						"%n<details>%n<summary>⚠️ %d new %s take 1 s or more</summary>%n%n| Test | Area | PR |%n| --- | --- | ---: |%n",
						newSlow.size(), newSlow.size() == 1 ? "test" : "tests"));
				newSlow.stream()
					.limit(30)
					.forEach(t -> md.append(String.format(Locale.ROOT, "| `%s` | `%s` | %s |%n", display(t.id()), areaOf(head, t),
							time(t.ms()))));
				md.append("\n</details>\n");
			}
			return md.toString();
		}

		boolean changedEverywhere(double now, double[] runs) {
			int direction = 0;
			for (double run : runs) {
				double diff = now - run;
				double ratio = Math.max(now, run) / Math.max(Math.min(now, run), 1);
				int sign = (int) Math.signum(diff);
				if (Math.abs(diff) < this.rules.minMs() || ratio < this.rules.minFactor()
						|| (direction != 0 && sign != direction)) {
					return false;
				}
				direction = sign;
			}
			return true;
		}

		/** One bar for main (median of its runs) and one for this pull request, per Java version. */
		private String chart(List<String> javas, String pr) {
			List<String> labels = new ArrayList<>();
			List<String> mainBars = new ArrayList<>();
			List<String> prBars = new ArrayList<>();
			double max = 1;
			for (String java : javas) {
				Report head = head(java);
				double[] runs = bases(java).stream().mapToDouble(Report::totalMs).toArray();
				double main = runs.length == 0 ? 0 : median(runs) / 1000;
				double now = head == null ? 0 : head.totalMs() / 1000;
				max = Math.max(max, Math.max(main, now));
				labels.add(quote("Java " + java + " · main " + (runs.length == 0 ? "no report" : seconds1(main))));
				labels.add(quote("Java " + java + " · this PR (" + pr + ") " + (head == null ? "no report" : seconds1(now))));
				mainBars.add(String.format(Locale.ROOT, "%.1f", main));
				mainBars.add("0");
				prBars.add("0");
				prBars.add(String.format(Locale.ROOT, "%.1f", now));
			}
			// Mermaid draws a second bar series on top of the first and starts every bar at the plot edge, so main and
			// this PR get their own rows, the value axis is hidden and each value sits in its row label.
			return String.join("\n", "```mermaid",
					"%%{init: {\"xyChart\": {\"height\": 360, \"yAxis\": {\"showLabel\": false, \"showTick\": false, "
							+ "\"showTitle\": false, \"showAxisLine\": false}}, \"themeVariables\": {\"xyChart\": "
							+ "{\"plotColorPalette\": \"#8c959f, #1f6feb\", \"backgroundColor\": \"transparent\"}}}}%%",
					"xychart-beta horizontal", "  title \"Summed test time: main vs this PR (" + pr + ")\"",
					"  x-axis [" + String.join(", ", labels) + "]",
					String.format(Locale.ROOT, "  y-axis 0 --> %d", (int) Math.ceil(max * 1.05)),
					"  bar [" + String.join(", ", mainBars) + "]", "  bar [" + String.join(", ", prBars) + "]", "```",
					"");
		}

		private double areaMs(Report report, String area) {
			return report.tests().values().stream().filter(t -> areaOf(report, t).equals(area)).mapToDouble(Test::ms).sum();
		}

	}

	// ---------------------------------------------------------------- formatting

	private static int runs(java.util.Collection<Test> tests) {
		return tests.stream().mapToInt(Test::runs).sum();
	}

	private static int rank(String mark) {
		return switch (mark) {
			case "❌" -> 0;
			case "⚠️" -> 1;
			default -> 2;
		};
	}

	static double median(double[] values) {
		if (values.length == 0) {
			return 0;
		}
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		int mid = sorted.length / 2;
		return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
	}

	private static String quote(String label) {
		return "\"" + label.replace("\"", "'") + "\"";
	}

	private static String number(int value) {
		return String.format(Locale.US, "%,d", value);
	}

	private static String signed(int value) {
		return value > 0 ? "+" + number(value) : value < 0 ? "−" + number(-value) : "0";
	}

	private static String arrow(Report base, int before, int now) {
		return base == null ? number(now) : number(before) + " → " + number(now);
	}

	private static String delta(Report base, int before, int now) {
		if (base == null) {
			return "";
		}
		String share = before == 0 ? "" : String.format(Locale.ROOT, " (%s%d%%)", now >= before ? "+" : "−",
				Math.round(Math.abs(now - before) * 100.0 / before));
		return signed(now - before) + share;
	}

	static String time(double ms) {
		return ms >= 1000 ? seconds1(ms / 1000) : String.format(Locale.ROOT, "%d ms", Math.round(ms));
	}

	private static String seconds1(double s) {
		return String.format(Locale.ROOT, "%.1f s", s);
	}

	private static String signedTime(double ms) {
		return (ms > 0 ? "+" : ms < 0 ? "−" : "") + time(Math.abs(ms));
	}

	private static String factor(double a, double b) {
		double r = Math.max(a, b) / Math.max(Math.min(a, b), 1);
		return r >= 10 ? Math.round(r) + "×" : String.format(Locale.ROOT, "%.1f×", r);
	}

	private static String require(Map<String, String> options, String key) {
		String value = options.get(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("missing --" + key);
		}
		return value;
	}

	// ---------------------------------------------------------------- selftest

	private static void selftest() {
		String otr = """
				<?xml version="1.0" ?>
				<e:events xmlns="https://schemas.opentest4j.org/reporting/core/0.2.0" xmlns:e="https://schemas.opentest4j.org/reporting/events/0.2.0" xmlns:java="https://schemas.opentest4j.org/reporting/java/0.2.0" xmlns:junit="https://schemas.junit.org/open-test-reporting">
				<e:started id="1" name="JUnit Jupiter" time="2026-01-01T00:00:00Z"><metadata><junit:type>CONTAINER</junit:type></metadata></e:started>
				<e:started id="2" name="A" parentId="1" time="2026-01-01T00:00:00Z"><metadata><tags><tag>api</tag><tag>api.options</tag></tags><junit:type>CONTAINER</junit:type></metadata><sources><java:classSource className="x.A"></java:classSource></sources></e:started>
				<e:started id="3" name="one()" parentId="2" time="2026-01-01T00:00:00.100Z"><metadata><tags><tag>api</tag><tag>api.options</tag></tags><junit:type>TEST</junit:type></metadata><sources><java:methodSource className="x.A" methodName="one" methodParameterTypes=""></java:methodSource></sources></e:started>
				<e:finished id="3" time="2026-01-01T00:00:00.350Z"><result status="SUCCESSFUL"></result></e:finished>
				<e:started id="4" name="two(String)" parentId="2" time="2026-01-01T00:00:00.400Z"><metadata><tags><tag>api</tag></tags><junit:type>CONTAINER</junit:type></metadata><sources><java:methodSource className="x.A" methodName="two" methodParameterTypes="java.lang.String"></java:methodSource></sources></e:started>
				<e:started id="5" name="[1] a" parentId="4" time="2026-01-01T00:00:00.400Z"><metadata><tags><tag>api</tag></tags><junit:type>TEST</junit:type></metadata></e:started>
				<e:finished id="5" time="2026-01-01T00:00:00.410Z"><result status="SUCCESSFUL"></result></e:finished>
				<e:started id="6" name="[2] b" parentId="4" time="2026-01-01T00:00:00.410Z"><metadata><tags><tag>api</tag></tags><junit:type>TEST</junit:type></metadata></e:started>
				<e:finished id="6" time="2026-01-01T00:00:00.430Z"><result status="SUCCESSFUL"></result></e:finished>
				<e:finished id="4" time="2026-01-01T00:00:00.430Z"><result status="SUCCESSFUL"></result></e:finished>
				<e:finished id="2" time="2026-01-01T00:00:01Z"><result status="SUCCESSFUL"></result></e:finished>
				</e:events>
				""";
		Map<String, Test> tests = new TreeMap<>();
		Map<String, Double> classes = new TreeMap<>();
		readOpenTestReport(new java.io.ByteArrayInputStream(otr.getBytes(StandardCharsets.UTF_8)), tests, classes);
		check(tests.size() == 2, "two tests from Open Test Reporting: " + tests);
		check(tests.get("x.A#one").ms() == 250 && tests.get("x.A#one").area().equals("api"), "plain test: " + tests);
		check(tests.get("x.A#two").runs() == 2 && tests.get("x.A#two").ms() == 30, "parameterized runs: " + tests);
		check(classes.get("x.A") == 1000, "class time includes setup: " + classes);

		String surefire = """
				<testsuites><testsuite name="x.B" time="1.5"><testcase name="three(int)[1]" classname="x.B" time="0.5"/>
				<testcase name="three(int)[2]" classname="x.B" time="0.25"/><testcase name="rule" classname="x.B" time="0.1"/></testsuite>
				<testsuite name="y.ArchTests" time="0.2"><testcase name="no_cycles" classname="ArchTests" time="0.2"/></testsuite></testsuites>
				""";
		Map<String, Test> sure = new TreeMap<>();
		Map<String, Double> sureClasses = new TreeMap<>();
		readSurefire(new java.io.ByteArrayInputStream(surefire.getBytes(StandardCharsets.UTF_8)), sure, sureClasses);
		check(sure.get("x.B#three").runs() == 2 && sure.get("x.B#three").ms() == 750, "Surefire runs: " + sure);
		check(sure.get("x.B#rule").area().isEmpty() && sureClasses.get("x.B") == 1500, "Surefire class: " + sureClasses);
		check(sure.containsKey("y.ArchTests#no_cycles"), "ArchUnit rules get the suite's full class name: " + sure);
		check(display("de.x.Outer$InnerTests#run").equals("Outer.InnerTests#run"), "display name");

		check(area(List.of("http", "http.errors")).equals("http") && area(List.of("tools.calls")).equals("tools")
				&& area(List.of()).equals(UNTAGGED), "area from tags");
		check(median(new double[] { 3, 1, 2 }) == 2 && median(new double[] { 4, 1, 2, 3 }) == 2.5, "median");

		Rules rules = new Rules(700, 2, 2000, 8000, 0.35, 0.5, 1000);
		Report base = report("25", "abc1234", Map.of("C#slow", 100.0, "C#same", 900.0, "C#gone", 50.0, "D#moved", 3000.0,
				"D#first", 200.0), Map.of("C", 2000.0, "D", 4000.0), 1);
		Report head = report("25", "def5678", Map.of("C#slow", 2000.0, "C#same", 1000.0, "C#new", 1500.0, "D#moved",
				200.0, "D#first", 3100.0), Map.of("C", 5000.0, "D", 4100.0), 0);
		Comparison c = new Comparison(List.of(head), List.of(base), "abc1234", "25", rules);
		check(c.changedEverywhere(2000, new double[] { 100, 150 }), "slower everywhere is flagged");
		check(!c.changedEverywhere(2000, new double[] { 100, 1900 }), "inside the noise is not flagged");
		check(!c.changedEverywhere(1000, new double[] { 100, 3000 }), "mixed directions are not flagged");
		String counts = c.counts("<!-- m -->");
		check(counts.contains("| ❌ | `untagged` |"), "untagged tests are marked: " + counts);
		String timing = c.timing("<!-- m -->", List.of("25", "21"), "#1");
		check(timing.contains("| ❌ | `C#slow` |"), "slower test with a slower class is a real cost: " + timing);
		check(timing.contains("| ⚠️ | `D#first` |"), "start-up moved into the first test: " + timing);
		check(timing.contains("| ✅ | `D#moved` |"), "faster test: " + timing);
		check(timing.contains("Java 21 · main no report") && timing.contains("`C#new` |"), "missing data and new slow test");
		check(!timing.contains("C#same"), "small change is not listed");

		// A pull request that removes a test's tags shows it as untagged, not under its old area.
		Report taggedBase = new Report("25", "abc1234", Map.of("p.E#x", new Test("p.E#x", "api", 1, 10), "p.E#y",
				new Test("p.E#y", "api", 1, 10)), Map.of(), 1, true);
		Report untaggedHead = new Report("25", "def5678", Map.of("p.E#x", new Test("p.E#x", "", 1, 10), "p.E#y",
				new Test("p.E#y", "api", 1, 10)), Map.of(), 0, true);
		String lost = new Comparison(List.of(untaggedHead), List.of(taggedBase), "abc1234", "25", rules)
			.counts("<!-- m -->");
		check(row(lost, "`untagged`").startsWith("| ❌ |") && row(lost, "`api`").contains("2 → 1"), "lost tags: " + lost);
		String noBase = new Comparison(List.of(untaggedHead), List.of(taggedBase), "0000000", "25", rules)
			.counts("<!-- m -->");
		check(!noBase.contains("→"), "no comparison without the exact base commit: " + noBase);
		System.out.println("selftest passed");
	}

	private static Report report(String java, String commit, Map<String, Double> tests, Map<String, Double> classes,
			int order) {
		Map<String, Test> map = new TreeMap<>();
		tests.forEach((id, ms) -> map.put(id, new Test(id, "", 1, ms)));
		return new Report(java, commit, map, new TreeMap<>(classes), order, false);
	}

	private static String row(String markdown, String needle) {
		return Arrays.stream(markdown.split("\n")).filter(l -> l.contains(needle)).findFirst().orElse("");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("selftest failed: " + what);
		}
	}

}
