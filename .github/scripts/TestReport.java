import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
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

import javax.imageio.ImageIO;
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
	record Rules(double minMs, double minFactor, double cellMs, double totalFailFactor, double classShare,
			double newSlowMs) {
	}

	record Test(String id, String area, int runs, double ms) {
	}

	record Report(String java, String commit, Map<String, Test> tests, Map<String, Double> classes, int order) {

		double totalMs() {
			return this.tests.values().stream().mapToDouble(Test::ms).sum();
		}

	}

	private TestReport() {
	}

	public static void main(String[] args) throws Exception {
		System.setProperty("java.awt.headless", "true");
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
		Report report = new Report(require(options, "java"), options.getOrDefault("commit", ""), tests, classes, 0);
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
		Map<String, Test> tests = new LinkedHashMap<>();
		Map<String, Double> classes = new LinkedHashMap<>();
		for (String line : text.split("\r?\n")) {
			String[] c = line.split("\t");
			switch (c[0]) {
				case "java" -> java = c.length > 1 ? c[1].replaceAll("[^0-9]", "") : "";
				case "commit" -> commit = c.length > 1 && SAFE_SHA.matcher(c[1]).matches() ? c[1] : "";
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
		return new Report(java, commit, tests, classes, order);
	}

	// ---------------------------------------------------------------- diff

	/** Where chart images may be published: this repository's raw files. Anything else is refused. */
	private static final Pattern SAFE_URL = Pattern
		.compile("https://raw\\.githubusercontent\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+");

	/**
	 * Options: {@code --head DIR} (this pull request's reports, {@code head.tsv}, plus a {@code base.tsv} when the
	 * pull request also tested its base commit), {@code --base DIR} (one numbered folder per {@code main} run, newest
	 * first), {@code --base-commit SHA}, {@code --head-commit SHA}, {@code --pr N}, {@code --java 25,21,17},
	 * {@code --tables-from 25}, {@code --chart-url URL} (where the PNG charts will be published; without it the
	 * comments show the numbers only) and {@code --out DIR}.
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
		String pr = "#" + require(options, "pr").replaceAll("[^0-9]", "");
		List<String> javas = Arrays.stream(options.getOrDefault("java", "25,21,17").split(","))
			.map(String::trim)
			.filter(v -> v.matches("[0-9]{1,3}"))
			.toList();
		Comparison comparison = new Comparison(heads, bases, options.getOrDefault("base-commit", ""),
				options.getOrDefault("tables-from", "25"), rules(options), javas, pr);
		Path out = Path.of(options.getOrDefault("out", "comments"));
		Files.createDirectories(out);
		String chartUrl = options.getOrDefault("chart-url", "").replaceAll("/+$", "");
		if (!chartUrl.isEmpty() && !SAFE_URL.matcher(chartUrl).matches()) {
			throw new IllegalArgumentException("--chart-url must point at raw.githubusercontent.com");
		}
		if (!chartUrl.isEmpty() && !comparison.drawCharts(out)) {
			chartUrl = "";
		}
		String head = options.getOrDefault("head-commit", "");
		Files.writeString(out.resolve("counts.md"), comparison.counts(marker("counts", head), chartUrl));
		Files.writeString(out.resolve("timing.md"), comparison.timing(marker("timing", head), chartUrl));
	}

	private static Rules rules(Map<String, String> o) {
		// Measured on this repository's CI from main runs of the same commit; CONTRIBUTING.md has the numbers.
		return new Rules(number(o, "min-ms", 3000), number(o, "min-factor", 2), number(o, "cell-ms", 1500),
				number(o, "total-fail-factor", 1.2), 0.5, 1000);
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

	/** {@code de.x.Outer$InnerTests#method} is shown as {@code Outer.InnerTests#method}. */
	static String display(String id) {
		int hash = id.indexOf('#');
		return hash < 0 ? simple(id) : simple(id.substring(0, hash)) + id.substring(hash);
	}

	static final class Comparison {

		private static final List<String> PARENTS = List.of("api", "http", "configuration", "samples", "tools",
				"streaming", "multimodal", "observability", "embeddings", "build-release");

		private final List<Report> heads;

		private final List<Report> bases;

		private final String baseCommit;

		private final String tablesFrom;

		private final Rules rules;

		private final List<String> javas;

		private final String pr;

		/** Area of every test id, from the tagged reports; untagged base reports borrow it by test id. */
		private final Map<String, String> areas = new HashMap<>();

		Comparison(List<Report> heads, List<Report> bases, String baseCommit, String tablesFrom, Rules rules,
				List<String> javas, String pr) {
			this.heads = heads;
			this.bases = bases;
			this.baseCommit = baseCommit;
			this.tablesFrom = heads.stream().anyMatch(r -> r.java().equals(tablesFrom)) || heads.isEmpty() ? tablesFrom
					: heads.get(0).java();
			this.rules = rules;
			this.javas = javas;
			this.pr = pr;
			Stream.concat(bases.stream(), heads.stream())
				.flatMap(r -> r.tests().values().stream())
				.filter(t -> !t.area().isEmpty() && !t.area().equals(UNTAGGED))
				.forEach(t -> this.areas.put(t.id(), t.area()));
		}

		private String areaOf(Test test) {
			return areaOf(test.id());
		}

		private String areaOf(String id) {
			return this.areas.getOrDefault(id, UNTAGGED);
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

		/** The main commit the pull request was tested against, else the newest main report. */
		private Report referenceBase(String java) {
			List<Report> candidates = bases(java);
			return candidates.stream()
				.filter(r -> !this.baseCommit.isEmpty() && r.commit().equals(this.baseCommit))
				.findFirst()
				.orElse(candidates.isEmpty() ? null : candidates.get(0));
		}

		private List<String> areaOrder(Stream<String> present) {
			Set<String> seen = present.collect(Collectors.toCollection(LinkedHashSet::new));
			List<String> order = new ArrayList<>(PARENTS.stream().filter(seen::contains).toList());
			seen.stream().filter(a -> !order.contains(a)).sorted().forEach(order::add);
			return order;
		}

		// ---------------------------------------------------------------- the five views

		record CountRow(String mark, String area, int before, int after, int runsBefore, int runsAfter, int added,
				int removed, int order) {

			boolean changed() {
				return !"✅".equals(this.mark) || this.added > 0 || this.removed > 0 || this.before != this.after
						|| this.runsBefore != this.runsAfter;
			}

		}

		/** View 5: tests per area against the main commit the pull request was tested against. */
		List<CountRow> countRows() {
			Report head = head(this.tablesFrom);
			if (head == null) {
				return List.of();
			}
			Report base = referenceBase(this.tablesFrom);
			Map<String, Test> before = base == null ? Map.of() : base.tests();
			List<CountRow> rows = new ArrayList<>();
			for (String area : areaOrder(
					Stream.concat(head.tests().values().stream(), before.values().stream()).map(this::areaOf))) {
				List<Test> b = before.values().stream().filter(t -> areaOf(t).equals(area)).toList();
				List<Test> n = head.tests().values().stream().filter(t -> areaOf(t).equals(area)).toList();
				int added = base == null ? 0 : (int) n.stream().filter(t -> !before.containsKey(t.id())).count();
				int removed = (int) b.stream().filter(t -> !head.tests().containsKey(t.id())).count();
				int loss = b.size() - n.size();
				String mark = area.equals(UNTAGGED) && !n.isEmpty() ? "❌"
						: !b.isEmpty() && loss > 0 && (n.isEmpty() || loss >= 0.1 * b.size()) ? "❌" : loss > 0 ? "⚠️" : "✅";
				rows.add(new CountRow(mark, area, b.size(), n.size(), runs(b), runs(n), added, removed, rows.size()));
			}
			rows.sort(Comparator.comparingInt((CountRow r) -> rank(r.mark())).thenComparingInt(CountRow::order));
			return rows;
		}

		record Total(String java, double[] runs, double median, double pr, boolean hasHead) {
		}

		/** View 1: the summed test time of this pull request against the range of the main runs. */
		List<Total> totals() {
			return this.javas.stream().map(java -> {
				Report head = head(java);
				double[] runs = bases(java).stream().mapToDouble(Report::totalMs).toArray();
				return new Total(java, runs, median(runs), head == null ? 0 : head.totalMs(), head != null);
			}).toList();
		}

		String totalMark(List<Total> totals) {
			List<Total> data = totals.stream().filter(t -> t.hasHead() && t.runs().length > 0).toList();
			if (data.isEmpty()) {
				return "✅";
			}
			boolean above = data.stream().allMatch(t -> t.pr() > Arrays.stream(t.runs()).max().orElse(0));
			boolean far = data.stream().allMatch(t -> t.pr() >= this.rules.totalFailFactor() * t.median());
			boolean enough = data.size() == this.javas.size() && data.stream().allMatch(t -> t.runs().length >= 3);
			return above && far && enough ? "❌" : above ? "⚠️" : "✅";
		}

		record Bridge(String java, double median, double added, double removed, double existing, double pr,
				int addedCount, int removedCount, int existingCount) {
		}

		/** View 2: main median + new tests − removed tests ± existing tests = this pull request. */
		Bridge bridge(String java) {
			Report head = head(java);
			List<Report> base = bases(java);
			if (head == null || base.isEmpty()) {
				return null;
			}
			Set<String> known = base.stream().flatMap(r -> r.tests().keySet().stream()).collect(Collectors.toSet());
			Report reference = referenceBase(java);
			double median = median(base.stream().mapToDouble(Report::totalMs).toArray());
			List<Test> added = head.tests().values().stream().filter(t -> !known.contains(t.id())).toList();
			List<String> removed = reference.tests()
				.keySet()
				.stream()
				.filter(id -> !head.tests().containsKey(id))
				.toList();
			double addedMs = added.stream().mapToDouble(Test::ms).sum();
			double removedMs = removed.stream()
				.mapToDouble(id -> median(base.stream()
					.map(r -> r.tests().get(id))
					.filter(Objects::nonNull)
					.mapToDouble(Test::ms)
					.toArray()))
				.sum();
			double pr = head.totalMs();
			// Existing tests take the rest, so the four steps add up to this pull request's total.
			return new Bridge(java, median, addedMs, removedMs, pr - median - addedMs + removedMs, pr, added.size(),
					removed.size(), head.tests().size() - added.size());
		}

		record Cell(boolean data, double delta, boolean up, boolean down, double over, double under) {
		}

		record AreaRow(String mark, String area, List<Cell> cells, int order) {
		}

		/** View 3: each area's existing tests against the main runs, per Java version. */
		List<AreaRow> areaRows() {
			Report tableHead = head(this.tablesFrom);
			if (tableHead == null) {
				return List.of();
			}
			List<AreaRow> rows = new ArrayList<>();
			for (String area : areaOrder(tableHead.tests().values().stream().map(this::areaOf))) {
				List<Cell> cells = new ArrayList<>();
				int ups = 0;
				int withData = 0;
				boolean fewRuns = false;
				for (String java : this.javas) {
					Report head = head(java);
					List<Report> base = bases(java);
					if (head == null || base.isEmpty()) {
						cells.add(new Cell(false, 0, false, false, 0, 0));
						continue;
					}
					Set<String> known = base.stream()
						.flatMap(r -> r.tests().keySet().stream())
						.collect(Collectors.toSet());
					List<Test> existing = head.tests()
						.values()
						.stream()
						.filter(t -> known.contains(t.id()) && areaOf(t).equals(area))
						.toList();
					double now = existing.stream().mapToDouble(Test::ms).sum();
					double[] runs = base.stream()
						.mapToDouble(r -> existing.stream()
							.map(t -> r.tests().get(t.id()))
							.filter(Objects::nonNull)
							.mapToDouble(Test::ms)
							.sum())
						.toArray();
					double over = now - Arrays.stream(runs).max().orElse(now);
					double under = Arrays.stream(runs).min().orElse(now) - now;
					boolean up = over >= this.rules.cellMs();
					cells.add(new Cell(true, now - median(runs), up, under >= this.rules.cellMs(), over, under));
					withData++;
					ups += up ? 1 : 0;
					fewRuns |= base.size() < 3;
				}
				// The Java versions run on different runners, so they are independent samples of the noise.
				String mark = withData >= 2 && ups == withData && !fewRuns ? "❌" : ups >= 2 || (withData == 1 && ups == 1) ? "⚠️" : "✅";
				rows.add(new AreaRow(mark, area, cells, rows.size()));
			}
			rows.sort(Comparator.comparingInt((AreaRow r) -> rank(r.mark())).thenComparingInt(AreaRow::order));
			return rows;
		}

		record TestRow(String mark, String id, String area, double main, double now, double classMain,
				double classNow, String perJava) {
		}

		/** View 4: single tests that changed beyond the noise on the table version, with the other versions beside. */
		List<TestRow> testRows() {
			Report head = head(this.tablesFrom);
			List<Report> base = bases(this.tablesFrom);
			if (head == null || base.isEmpty()) {
				return List.of();
			}
			Map<String, Double> addedPerClass = new HashMap<>();
			List<Test> changed = new ArrayList<>();
			for (Test test : head.tests().values()) {
				double[] runs = timesOf(base, test.id());
				if (runs.length > 0 && changedEverywhere(test.ms(), runs)) {
					changed.add(test);
					if (test.ms() > median(runs)) {
						addedPerClass.merge(classOf(test.id()), test.ms() - median(runs), Double::sum);
					}
				}
			}
			List<TestRow> rows = new ArrayList<>();
			for (Test test : changed) {
				String cls = classOf(test.id());
				double main = median(timesOf(base, test.id()));
				double classMain = median(base.stream()
					.map(r -> r.classes().get(cls))
					.filter(Objects::nonNull)
					.mapToDouble(Double::doubleValue)
					.toArray());
				double classNow = head.classes().getOrDefault(cls, 0.0);
				String mark = "✅";
				if (test.ms() > main) {
					double grew = classNow - classMain;
					// A real cost when the class grew by at least half of what its slower tests added; otherwise
					// start-up work (a Spring context, an HTTP client) probably moved into this test.
					mark = grew >= this.rules.minMs() && grew >= this.rules.classShare() * addedPerClass.get(cls) ? "❌"
							: "⚠️";
				}
				rows.add(new TestRow(mark, test.id(), areaOf(test), main, test.ms(), classMain, classNow,
						perJava(test.id())));
			}
			rows.sort(Comparator.comparingInt((TestRow r) -> rank(r.mark()))
				.thenComparingDouble(r -> -Math.abs(r.now() - r.main())));
			return rows;
		}

		/** One square per Java version: 🟥 slower, 🟦 faster beyond the noise, · no change, — no data. */
		private String perJava(String id) {
			StringBuilder out = new StringBuilder();
			for (String java : this.javas) {
				Report head = head(java);
				double[] runs = timesOf(bases(java), id);
				Test test = head == null ? null : head.tests().get(id);
				out.append(test == null || runs.length == 0 ? "—"
						: !changedEverywhere(test.ms(), runs) ? "·" : test.ms() > median(runs) ? "🟥" : "🟦");
			}
			return out.toString();
		}

		private static double[] timesOf(List<Report> base, String id) {
			return base.stream().map(r -> r.tests().get(id)).filter(Objects::nonNull).mapToDouble(Test::ms).toArray();
		}

		private static String classOf(String id) {
			return id.substring(0, id.indexOf('#'));
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

		private List<Test> newSlow() {
			Report head = head(this.tablesFrom);
			List<Report> base = bases(this.tablesFrom);
			if (head == null || base.isEmpty()) {
				return List.of();
			}
			Set<String> known = base.stream().flatMap(r -> r.tests().keySet().stream()).collect(Collectors.toSet());
			return head.tests()
				.values()
				.stream()
				.filter(t -> !known.contains(t.id()) && t.ms() >= this.rules.newSlowMs())
				.sorted(Comparator.comparingDouble(t -> -t.ms()))
				.toList();
		}

		// ---------------------------------------------------------------- Markdown

		/** A chart image with its numbers collapsed below it, or the numbers alone when there is no image. */
		private static void section(StringBuilder md, String chartUrl, String file, String alt, String table) {
			if (chartUrl.isEmpty()) {
				md.append(table).append('\n');
				return;
			}
			md.append("![").append(alt).append("](").append(chartUrl).append('/').append(file).append(")\n\n");
			md.append("<details>\n<summary>Numbers</summary>\n\n").append(table).append("\n</details>\n\n");
		}

		String counts(String marker, String chartUrl) {
			StringBuilder md = new StringBuilder(marker).append("\n### Test area\n\n");
			List<CountRow> rows = countRows();
			if (rows.isEmpty()) {
				return md.append("No test report.\n").toString();
			}
			boolean hasBase = referenceBase(this.tablesFrom) != null;
			StringBuilder t = new StringBuilder("| | Area | Tests | Δ | Added | Removed | Runs Δ |\n");
			t.append("| :-: | --- | ---: | ---: | ---: | ---: | ---: |\n");
			for (CountRow r : rows) {
				if (r.changed() || !hasBase) {
					t.append(String.format(Locale.ROOT, "| %s | `%s` | %s | %s | %d | %d | %s |%n", r.mark(), r.area(),
							hasBase ? number(r.before()) + " → " + number(r.after()) : number(r.after()),
							hasBase ? signed(r.after() - r.before()) : "", r.added(), r.removed(),
							hasBase ? signed(r.runsAfter() - r.runsBefore()) : ""));
				}
			}
			List<CountRow> unchanged = rows.stream().filter(r -> hasBase && !r.changed()).toList();
			if (!unchanged.isEmpty()) {
				t.append(String.format(Locale.ROOT, "| ✅ | %d %s unchanged | %s | | | | |%n", unchanged.size(),
						unchanged.size() == 1 ? "area" : "areas",
						number(unchanged.stream().mapToInt(CountRow::after).sum())));
			}
			int before = rows.stream().mapToInt(CountRow::before).sum();
			int after = rows.stream().mapToInt(CountRow::after).sum();
			t.append(String.format(Locale.ROOT, "| | **Total** | **%s** | **%s** | **%d** | **%d** | **%s** |%n",
					hasBase ? number(before) + " → " + number(after) : number(after), hasBase ? signed(after - before) : "",
					rows.stream().mapToInt(CountRow::added).sum(), rows.stream().mapToInt(CountRow::removed).sum(),
					hasBase ? signed(rows.stream().mapToInt(r -> r.runsAfter() - r.runsBefore()).sum()) : ""));
			section(md, chartUrl, "counts.png", "Tests added and removed per area", t.toString());
			return md.toString();
		}

		String timing(String marker, String chartUrl) {
			StringBuilder md = new StringBuilder(marker).append("\n### Test time\n\n");
			List<Total> totals = totals();
			String mark = totalMark(totals);
			StringBuilder t1 = new StringBuilder("| | Java | main | this PR (" + this.pr + ") | × median |\n");
			t1.append("| :-: | --- | ---: | ---: | ---: |\n");
			for (int i = 0; i < totals.size(); i++) {
				Total t = totals.get(i);
				double[] runs = t.runs();
				t1.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s |%n", i == 0 ? mark : "", t.java(),
						runs.length == 0 ? "no report"
								: seconds1(Arrays.stream(runs).min().orElse(0) / 1000) + " – "
										+ seconds1(Arrays.stream(runs).max().orElse(0) / 1000) + " · " + runs.length
										+ (runs.length == 1 ? " run" : " runs"),
						t.hasHead() ? seconds1(t.pr() / 1000) : "no report",
						t.hasHead() && runs.length > 0 ? String.format(Locale.ROOT, "%.2f×", t.pr() / t.median()) : "—"));
			}
			section(md, chartUrl, "range.png", "Summed test time against the main range, per Java version",
					t1.toString());

			List<Bridge> bridges = this.javas.stream().map(this::bridge).filter(Objects::nonNull).toList();
			if (!bridges.isEmpty()) {
				Bridge first = bridges.get(0);
				boolean newCost = bridges.stream().allMatch(b -> b.added() >= this.rules.minMs());
				StringBuilder t2 = new StringBuilder("| | Change |");
				bridges.forEach(b -> t2.append(" Java ").append(b.java()).append(" |"));
				t2.append("\n| :-: | --- |").append(" ---: |".repeat(bridges.size())).append('\n');
				t2.append("| | main median |");
				bridges.forEach(b -> t2.append(' ').append(seconds1(b.median() / 1000)).append(" |"));
				t2.append('\n').append(newCost ? "| ⚠️ |" : "| ✅ |").append(' ').append(number(first.addedCount()))
					.append(" new tests |");
				bridges.forEach(b -> t2.append(' ').append(signedSeconds(b.added())).append(" |"));
				t2.append("\n| ✅ | ").append(number(first.removedCount())).append(" removed tests |");
				bridges.forEach(b -> t2.append(' ').append(signedSeconds(-b.removed())).append(" |"));
				t2.append("\n| | ").append(number(first.existingCount())).append(" existing tests |");
				bridges.forEach(b -> t2.append(' ').append(signedSeconds(b.existing())).append(" |"));
				t2.append("\n| | **this PR (").append(this.pr).append(")** |");
				bridges.forEach(b -> t2.append(" **").append(seconds1(b.pr() / 1000)).append("** |"));
				t2.append('\n');
				section(md, chartUrl, "bridge.png", "Where the change in test time comes from", t2.toString());
			}

			List<AreaRow> areaRows = areaRows();
			if (bridges.size() > 0 && !areaRows.isEmpty()) {
				md.append("#### Areas · existing tests\n\n");
				StringBuilder t3 = new StringBuilder("| | Area |");
				this.javas.forEach(j -> t3.append(" Java ").append(j).append(" |"));
				t3.append("\n| :-: | --- |").append(" ---: |".repeat(this.javas.size())).append('\n');
				for (AreaRow r : areaRows) {
					t3.append("| ").append(r.mark()).append(" | `").append(r.area()).append("` |");
					for (Cell c : r.cells()) {
						t3.append(' ')
							.append(!c.data() ? "—" : (c.up() ? "🟥 " : c.down() ? "🟦 " : "") + signedTime(c.delta()))
							.append(" |");
					}
					t3.append('\n');
				}
				section(md, chartUrl, "areas.png", "Change of each area's existing tests, per Java version", t3.toString());
			}

			List<TestRow> testRows = testRows();
			if (!testRows.isEmpty()) {
				long slower = testRows.stream().filter(r -> r.now() > r.main()).count();
				md.append(String.format(Locale.ROOT, "#### %d slower · %d faster · Java %s%n%n", slower,
						testRows.size() - slower, this.tablesFrom));
				StringBuilder t4 = new StringBuilder("| | Area | main | PR | × | ")
					.append(nbsp(String.join(" ", this.javas)))
					.append(" | Class main → PR | Test |\n| :-: | --- | ---: | ---: | ---: | :-: | ---: | --- |\n");
				testRows.stream()
					.limit(30)
					.forEach(r -> t4.append(String.format(Locale.ROOT, "| %s | `%s` | %s | %s | %s | %s | %s | `%s` |%n",
							r.mark(), r.area(), nbsp(time(r.main())), nbsp(time(r.now())), factor(r.main(), r.now()),
							joined(r.perJava()), nbsp(seconds1(r.classMain() / 1000) + " → " + seconds1(r.classNow() / 1000)),
							display(r.id()))));
				if (testRows.size() > 30) {
					t4.append(String.format(Locale.ROOT, "| | | | | | | | … and %d more |%n", testRows.size() - 30));
				}
				section(md, chartUrl, "tests.png", "Tests that got slower or faster", t4.toString());
			}

			List<Test> newSlow = newSlow();
			if (!newSlow.isEmpty()) {
				md.append(String.format(Locale.ROOT,
						"<details>%n<summary>⚠️ %d new %s take 1 s or more · Java %s</summary>%n%n| Test | Area | PR |%n| --- | --- | ---: |%n",
						newSlow.size(), newSlow.size() == 1 ? "test" : "tests", this.tablesFrom));
				newSlow.stream()
					.limit(30)
					.forEach(t -> md.append(String.format(Locale.ROOT, "| `%s` | `%s` | %s |%n", display(t.id()),
							areaOf(t), time(t.ms()))));
				md.append("\n</details>\n");
			}
			return md.toString();
		}

		// ---------------------------------------------------------------- charts

		/** Draws the PNG charts into {@code out}; false when this JVM cannot draw (no fonts, for example). */
		boolean drawCharts(Path out) {
			try {
				Charts.range(totals(), totalMark(totals()), this.pr).save(out.resolve("range.png"));
				List<Bridge> bridges = this.javas.stream().map(this::bridge).filter(Objects::nonNull).toList();
				if (!bridges.isEmpty()) {
					Charts.bridge(bridges, this.pr).save(out.resolve("bridge.png"));
					Charts.areas(areaRows(), this.javas, this.rules.cellMs()).save(out.resolve("areas.png"));
				}
				List<TestRow> tests = testRows();
				if (!tests.isEmpty()) {
					Charts.tests(tests.stream().limit(15).toList(), this.javas, this.pr).save(out.resolve("tests.png"));
				}
				Charts.counts(countRows(), referenceBase(this.tablesFrom) != null).save(out.resolve("counts.png"));
				return true;
			}
			catch (RuntimeException | Error | IOException ex) {
				System.err.println("charts skipped: " + ex);
				return false;
			}
		}

	}

	// ---------------------------------------------------------------- PNG charts (Java2D, no dependencies)

	static final class Charts {

		static final int W = 720;

		static final Color BG = new Color(0xffffff);

		static final Color FG = new Color(0x1f2328);

		static final Color MUTED = new Color(0x59636e);

		static final Color GRID = new Color(0xd1d9e0);

		static final Color RANGE = new Color(0xd1d9e0);

		static final Color RUN = new Color(0x818b98);

		static final Color PR = new Color(0x8250df);

		static final Color UP = new Color(0xcf222e);

		static final Color DOWN = new Color(0x0969da);

		static final Color NEW = new Color(0xbf8700);

		static final Color ADD = new Color(0x1a7f37);

		static final Color REM = new Color(0xbc4c00);

		static final Color CELL = new Color(0xf6f8fa);

		static final Font SANS = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

		static final Font BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12);

		static final Font SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

		static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 11);

		private final BufferedImage image;

		private final Graphics2D g;

		private Charts(int height) {
			this.image = new BufferedImage(W * 2, height * 2, BufferedImage.TYPE_INT_RGB);
			this.g = this.image.createGraphics();
			this.g.scale(2, 2);
			this.g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			this.g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			this.g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			this.g.setColor(BG);
			this.g.fillRect(0, 0, W, height);
		}

		void save(Path file) throws IOException {
			this.g.dispose();
			ImageIO.write(this.image, "png", file.toFile());
		}

		/** Align: -1 left, 0 centre, 1 right; {@code y} is the baseline. */
		private void text(double x, double y, String s, Color color, Font font, int align) {
			this.g.setFont(font);
			this.g.setColor(color);
			int width = this.g.getFontMetrics().stringWidth(s);
			this.g.drawString(s, (float) (align < 0 ? x : align == 0 ? x - width / 2.0 : x - width), (float) y);
		}

		private void rect(double x, double y, double w, double h, Color color, double arc) {
			this.g.setColor(color);
			this.g.fill(new RoundRectangle2D.Double(x, y, Math.max(0, w), h, arc, arc));
		}

		private void line(double x1, double y1, double x2, double y2, Color color, float width) {
			this.g.setColor(color);
			this.g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			this.g.draw(new Line2D.Double(x1, y1, x2, y2));
		}

		private void dot(double x, double y, double r, Color color, boolean ring) {
			if (ring) {
				this.g.setColor(BG);
				this.g.fill(new Ellipse2D.Double(x - r - 2, y - r - 2, 2 * r + 4, 2 * r + 4));
			}
			this.g.setColor(color);
			this.g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
		}

		/** The row marks, drawn as shapes: a red cross, an amber triangle or a green tick. */
		private void mark(String mark, double x, double cy) {
			switch (mark) {
				case "❌" -> {
					line(x + 2, cy - 5, x + 12, cy + 5, UP, 2.4f);
					line(x + 12, cy - 5, x + 2, cy + 5, UP, 2.4f);
				}
				case "⚠️" -> {
					Path2D.Double tri = new Path2D.Double();
					tri.moveTo(x + 7, cy - 7);
					tri.lineTo(x + 14, cy + 6);
					tri.lineTo(x, cy + 6);
					tri.closePath();
					this.g.setColor(NEW);
					this.g.fill(tri);
					line(x + 7, cy - 2, x + 7, cy + 1.5, BG, 1.6f);
					dot(x + 7, cy + 3.8, 0.9, BG, false);
				}
				default -> {
					rect(x, cy - 7, 14, 14, ADD, 4);
					line(x + 3.5, cy, x + 6, cy + 3, BG, 1.8f);
					line(x + 6, cy + 3, x + 10.5, cy - 3, BG, 1.8f);
				}
			}
		}

		private static String cut(String s, int max) {
			return s.length() <= max ? s : s.substring(0, max - 1) + "…";
		}

		static Charts range(List<Comparison.Total> totals, String mark, String pr) {
			int top = 40;
			int rowH = 46;
			Charts c = new Charts(top + totals.size() * rowH + 28);
			double[] all = totals.stream()
				.flatMapToDouble(t -> java.util.stream.DoubleStream.concat(Arrays.stream(t.runs()),
						t.hasHead() ? java.util.stream.DoubleStream.of(t.pr()) : java.util.stream.DoubleStream.empty()))
				.map(ms -> ms / 1000)
				.toArray();
			double lo = all.length == 0 ? 0 : Math.floor(Arrays.stream(all).min().orElse(0) / 10) * 10;
			double hi = all.length == 0 ? 10 : Math.max(lo + 10, Math.ceil(Arrays.stream(all).max().orElse(0) / 10) * 10);
			double left = 90;
			double right = 200;
			java.util.function.DoubleUnaryOperator x = ms -> left + (ms / 1000 - lo) / (hi - lo) * (W - left - right);
			c.rect(left, 12, 26, 10, RANGE, 10);
			c.text(left + 32, 21, "main range", MUTED, SANS, -1);
			c.dot(left + 112, 17, 3, RUN, false);
			c.text(left + 120, 21, "one main run", MUTED, SANS, -1);
			c.line(left + 212, 10, left + 212, 24, FG, 2);
			c.text(left + 220, 21, "main median", MUTED, SANS, -1);
			c.dot(left + 314, 17, 6, PR, false);
			c.text(left + 324, 21, "this PR (" + pr + ")", MUTED, SANS, -1);
			int step = hi - lo > 80 ? 20 : 10;
			for (double t = lo; t <= hi + 0.01; t += step) {
				double gx = x.applyAsDouble(t * 1000);
				c.line(gx, top - 4, gx, top + totals.size() * rowH, GRID, 1);
				c.text(gx, top + totals.size() * rowH + 16, String.format(Locale.ROOT, "%.0f s", t), MUTED, SMALL, 0);
			}
			for (int i = 0; i < totals.size(); i++) {
				Comparison.Total t = totals.get(i);
				double cy = top + i * rowH + rowH / 2.0;
				if (i == 0) {
					c.mark(mark, 4, cy);
				}
				c.text(24, cy + 4, "Java " + t.java(), FG, BOLD, -1);
				if (t.runs().length > 0) {
					double min = Arrays.stream(t.runs()).min().orElse(0);
					double max = Arrays.stream(t.runs()).max().orElse(0);
					c.rect(x.applyAsDouble(min) - 6, cy - 6, x.applyAsDouble(max) - x.applyAsDouble(min) + 12, 12, RANGE, 12);
					for (double run : t.runs()) {
						c.dot(x.applyAsDouble(run), cy, 3, RUN, false);
					}
					c.line(x.applyAsDouble(t.median()), cy - 12, x.applyAsDouble(t.median()), cy + 12, FG, 2);
				}
				if (t.hasHead()) {
					c.dot(x.applyAsDouble(t.pr()), cy, 6.5, PR, true);
					c.text(W - right + 18, cy + 4, seconds1(t.pr() / 1000), FG, BOLD, -1);
					if (t.runs().length > 0) {
						double f = t.pr() / t.median();
						c.text(W - right + 72, cy + 4, String.format(Locale.ROOT, "%.2f× median", f), f >= 1.2 ? UP : MUTED,
								SANS, -1);
					}
				}
				else {
					c.text(W - right + 18, cy + 4, "no report", MUTED, SANS, -1);
				}
			}
			return c;
		}

		static Charts bridge(List<Comparison.Bridge> rows, String pr) {
			Comparison.Bridge first = rows.get(0);
			String[] labels = { "main median", number(first.addedCount()) + " new tests",
					number(first.removedCount()) + " removed", number(first.existingCount()) + " existing",
					"this PR (" + pr + ")" };
			double left = 120;
			double gap = 16;
			int top = 30;
			int rowH = 30;
			double pw = (W - left - gap * (rows.size() - 1)) / rows.size();
			double max = rows.stream()
				.mapToDouble(r -> Math.max(Math.max(r.median(), r.median() + r.added()), r.pr()))
				.max()
				.orElse(1) * 1.35;
			Charts c = new Charts(top + 5 * rowH + 6);
			for (int i = 0; i < labels.length; i++) {
				c.text(0, top + i * rowH + rowH / 2.0 + 4, labels[i], FG, i == 0 || i == 4 ? BOLD : SANS, -1);
			}
			for (int p = 0; p < rows.size(); p++) {
				Comparison.Bridge r = rows.get(p);
				double x0 = left + p * (pw + gap);
				java.util.function.DoubleUnaryOperator x = ms -> x0 + ms / max * pw;
				c.text(x0, 16, "Java " + r.java(), FG, BOLD, -1);
				c.line(x0, top - 4, x0, top + 5 * rowH, GRID, 1);
				double afterRemoved = r.median() + r.added() - r.removed();
				double[][] steps = { { 0, r.median() }, { r.median(), r.median() + r.added() },
						{ afterRemoved, r.median() + r.added() }, { Math.min(r.pr(), afterRemoved), Math.max(r.pr(), afterRemoved) },
						{ 0, r.pr() } };
				Color[] colors = { RUN, NEW, DOWN, r.existing() >= 0 ? UP : DOWN, PR };
				String[] values = { seconds1(r.median() / 1000), signedSeconds(r.added()), signedSeconds(-r.removed()),
						signedSeconds(r.existing()), seconds1(r.pr() / 1000) };
				for (int i = 0; i < 5; i++) {
					double y = top + i * rowH + 7;
					double a = x.applyAsDouble(steps[i][0]);
					double b = x.applyAsDouble(steps[i][1]);
					c.rect(a, y, Math.max(1.5, b - a), rowH - 14, colors[i], 3);
					c.text(b + 5, y + rowH / 2.0 - 3, values[i], i == 0 || i == 4 ? FG : MUTED, i == 0 || i == 4 ? BOLD : SANS, -1);
				}
			}
			return c;
		}

		static Charts areas(List<Comparison.AreaRow> rows, List<String> javas, double cellMs) {
			double left = 170;
			int top = 28;
			int rowH = 28;
			double cw = (W - left) / javas.size();
			Charts c = new Charts(top + rows.size() * rowH + 28);
			for (int j = 0; j < javas.size(); j++) {
				c.text(left + j * cw + cw / 2, 18, "Java " + javas.get(j), FG, BOLD, 0);
			}
			for (int i = 0; i < rows.size(); i++) {
				Comparison.AreaRow r = rows.get(i);
				double y = top + i * rowH;
				c.mark(r.mark(), 4, y + rowH / 2.0);
				c.text(26, y + rowH / 2.0 + 4, r.area(), FG, MONO, -1);
				for (int j = 0; j < r.cells().size(); j++) {
					Comparison.Cell cell = r.cells().get(j);
					double x0 = left + j * cw;
					Color fill = CELL;
					if (cell.up() || cell.down()) {
						double strength = Math.min(1, 0.35 + (cell.up() ? cell.over() : cell.under()) / 8000);
						fill = blend(cell.up() ? UP : DOWN, strength);
					}
					c.rect(x0 + 2, y + 2, cw - 4, rowH - 4, fill, 6);
					c.text(x0 + cw / 2, y + rowH / 2.0 + 4, cell.data() ? signedTime(cell.delta()) : "—",
							cell.up() || cell.down() ? FG : MUTED, cell.up() || cell.down() ? BOLD : SANS, 0);
				}
			}
			double ly = top + rows.size() * rowH + 18;
			c.rect(left, ly - 10, 14, 12, blend(UP, 0.7), 3);
			c.text(left + 20, ly, seconds1(cellMs / 1000) + " or more above the slowest main run", MUTED, SANS, -1);
			c.rect(left + 290, ly - 10, 14, 12, blend(DOWN, 0.7), 3);
			c.text(left + 310, ly, seconds1(cellMs / 1000) + " or more below the fastest", MUTED, SANS, -1);
			return c;
		}

		static Charts tests(List<Comparison.TestRow> rows, List<String> javas, String pr) {
			double left = 320;
			double right = 120;
			int top = 30;
			int rowH = 28;
			Charts c = new Charts(top + rows.size() * rowH + 28);
			double lo = 1;
			double hi = Math.log10(Math.max(20000, rows.stream().mapToDouble(r -> Math.max(r.main(), r.now())).max().orElse(1) * 1.5));
			java.util.function.DoubleUnaryOperator x = ms -> left + (Math.log10(Math.max(ms, 10)) - lo) / (hi - lo) * (W - left - right);
			for (double ms : new double[] { 10, 100, 1000, 10000, 100000 }) {
				if (Math.log10(ms) <= hi) {
					c.line(x.applyAsDouble(ms), top - 6, x.applyAsDouble(ms), top + rows.size() * rowH, GRID, 1);
					c.text(x.applyAsDouble(ms), 16, ms >= 1000 ? (int) (ms / 1000) + " s" : (int) ms + " ms", MUTED, SMALL, 0);
				}
			}
			c.text(W - right + 10, 16, String.join(" ", javas), MUTED, SMALL, -1);
			for (int i = 0; i < rows.size(); i++) {
				Comparison.TestRow r = rows.get(i);
				double cy = top + i * rowH + rowH / 2.0;
				c.mark(r.mark(), 2, cy);
				c.text(22, cy + 4, cut(display(r.id()), 46), FG, MONO, -1);
				boolean slower = r.now() > r.main();
				c.line(x.applyAsDouble(r.main()), cy, x.applyAsDouble(r.now()), cy, slower ? UP : DOWN, 3);
				c.dot(x.applyAsDouble(r.main()), cy, 4.5, RUN, false);
				c.dot(x.applyAsDouble(r.now()), cy, 5.5, PR, true);
				int k = 0;
				for (int cp : r.perJava().codePoints().toArray()) {
					String ch = new String(Character.toChars(cp));
					if (ch.equals("\u2060") || ch.equals("\uFE0F")) {
						continue;
					}
					double sx = W - right + 10 + k * 18;
					if (ch.equals("—")) {
						c.text(sx + 6, cy + 4, "—", MUTED, SANS, 0);
					}
					else {
						c.rect(sx, cy - 6, 12, 12, ch.equals("🟥") ? UP : ch.equals("🟦") ? DOWN : CELL, 3);
					}
					k++;
				}
				c.text(W - 8, cy + 4, factor(r.main(), r.now()), MUTED, SANS, 1);
			}
			double ly = top + rows.size() * rowH + 18;
			c.dot(left + 6, ly - 4, 4.5, RUN, false);
			c.text(left + 16, ly, "main median", MUTED, SANS, -1);
			c.dot(left + 116, ly - 4, 5.5, PR, false);
			c.text(left + 126, ly, "this PR (" + pr + ")", MUTED, SANS, -1);
			return c;
		}

		static Charts counts(List<Comparison.CountRow> all, boolean hasBase) {
			List<Comparison.CountRow> rows = all.stream().filter(r -> r.changed() || !hasBase).toList();
			int unchanged = all.size() - rows.size();
			double left = 170;
			double right = 170;
			int top = rows.isEmpty() ? 4 : 28;
			int rowH = 24;
			Charts c = new Charts(top + (rows.size() + (unchanged > 0 ? 1 : 0)) * rowH + 8);
			double mid = left + (W - left - right) / 2;
			double half = (W - left - right) / 2 - 8;
			double max = Math.max(1, rows.stream().mapToInt(r -> Math.max(r.added(), r.removed())).max().orElse(1));
			if (!rows.isEmpty()) {
				c.text(mid - 8, 16, "removed", MUTED, SANS, 1);
				c.text(mid + 8, 16, "added", MUTED, SANS, -1);
				c.text(W - right + 16, 16, hasBase ? "tests: main → PR" : "tests", MUTED, SANS, -1);
			}
			for (int i = 0; i < rows.size(); i++) {
				Comparison.CountRow r = rows.get(i);
				double y = top + i * rowH;
				c.mark(r.mark(), 4, y + rowH / 2.0);
				c.text(26, y + rowH / 2.0 + 4, r.area(), FG, MONO, -1);
				double wr = r.removed() / max * half;
				double wa = r.added() / max * half;
				c.rect(mid - wr, y + 5, wr, rowH - 10, REM, 3);
				c.rect(mid, y + 5, wa, rowH - 10, ADD, 3);
				c.text(mid - wr - 4, y + rowH / 2.0 + 4, String.valueOf(r.removed()), MUTED, SMALL, 1);
				c.text(mid + wa + 4, y + rowH / 2.0 + 4, String.valueOf(r.added()), MUTED, SMALL, -1);
				int net = r.after() - r.before();
				c.text(W - right + 16, y + rowH / 2.0 + 4, hasBase ? number(r.before()) + " → " + number(r.after())
						: number(r.after()), FG, SANS, -1);
				if (hasBase) {
					c.text(W - 6, y + rowH / 2.0 + 4, signed(net), net < 0 ? UP : FG, BOLD, 1);
				}
			}
			if (unchanged > 0) {
				double y = top + rows.size() * rowH;
				c.mark("✅", 4, y + rowH / 2.0);
				c.text(26, y + rowH / 2.0 + 4, unchanged + (unchanged == 1 ? " area" : " areas") + " unchanged", MUTED, SANS,
						-1);
			}
			if (!rows.isEmpty()) {
				c.line(mid, top - 2, mid, top + rows.size() * rowH, FG, 1);
			}
			return c;
		}

		private static Color blend(Color color, double alpha) {
			return new Color((int) Math.round(color.getRed() * alpha + 255 * (1 - alpha)),
					(int) Math.round(color.getGreen() * alpha + 255 * (1 - alpha)),
					(int) Math.round(color.getBlue() * alpha + 255 * (1 - alpha)));
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

	private static String number(int value) {
		return String.format(Locale.US, "%,d", value);
	}

	private static String signed(int value) {
		return value > 0 ? "+" + number(value) : value < 0 ? "−" + number(-value) : "0";
	}

	static String time(double ms) {
		return Math.abs(ms) >= 1000 ? seconds1(ms / 1000) : String.format(Locale.ROOT, "%d ms", Math.round(ms));
	}

	private static String seconds1(double s) {
		return String.format(Locale.ROOT, "%.1f s", s);
	}

	private static String signedSeconds(double ms) {
		return (ms >= 0 ? "+" : "−") + seconds1(Math.abs(ms) / 1000);
	}

	private static String signedTime(double ms) {
		return Math.abs(ms) < 0.5 ? "0 ms" : (ms > 0 ? "+" : "−") + time(Math.abs(ms));
	}

	private static String factor(double a, double b) {
		double r = Math.max(a, b) / Math.max(Math.min(a, b), 1);
		return r >= 10 ? Math.round(r) + "×" : String.format(Locale.ROOT, "%.1f×", r);
	}

	/** Short table cells must not wrap next to a long test name. */
	private static String nbsp(String s) {
		return s.replace(' ', '\u00A0');
	}

	/** Word joiners keep a row of emoji squares on one line. */
	private static String joined(String s) {
		return s.codePoints()
			.mapToObj(cp -> new String(Character.toChars(cp)))
			.collect(Collectors.joining("\u2060"))
			.replace("\u2060\uFE0F", "\uFE0F");
	}

	private static String require(Map<String, String> options, String key) {
		String value = options.get(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("missing --" + key);
		}
		return value;
	}

	// ---------------------------------------------------------------- selftest

	private static void selftest() throws IOException {
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

		Rules rules = new Rules(700, 2, 1500, 1.2, 0.5, 1000);
		Report base = report("25", "abc1234", Map.of("p.C#slow", 100.0, "p.C#same", 900.0, "p.C#gone", 50.0, "p.D#moved",
				3000.0, "p.D#first", 200.0), Map.of("p.C", 2000.0, "p.D", 4000.0), 1);
		Report head = report("25", "def5678", Map.of("p.C#slow", 2000.0, "p.C#same", 1000.0, "p.C#new", 1500.0,
				"p.D#moved", 200.0, "p.D#first", 3100.0), Map.of("p.C", 5000.0, "p.D", 4100.0), 0);
		Comparison c = new Comparison(List.of(head), List.of(base), "abc1234", "25", rules, List.of("25", "21"), "#1");
		check(c.changedEverywhere(2000, new double[] { 100, 150 }), "slower everywhere is flagged");
		check(!c.changedEverywhere(2000, new double[] { 100, 1900 }), "inside the noise is not flagged");
		check(!c.changedEverywhere(1000, new double[] { 100, 3000 }), "mixed directions are not flagged");
		String counts = c.counts("<!-- m -->", "");
		check(counts.contains("| ❌ | `untagged` |"), "untagged tests are marked: " + counts);
		String timing = c.timing("<!-- m -->", "");
		check(row(timing, "`C#slow`").startsWith("| ❌ |"), "slower test with a slower class is a real cost: " + timing);
		check(row(timing, "`D#first`").startsWith("| ⚠️ |"), "start-up moved into the first test: " + timing);
		check(row(timing, "`D#moved`").startsWith("| ✅ |"), "faster test: " + timing);
		check(row(timing, "| 21 |").contains("no report") && timing.contains("`C#new` |"), "missing data and new test");
		check(!timing.contains("C#same"), "small change is not listed");
		Comparison.Bridge bridge = c.bridge("25");
		check(Math.abs(bridge.median() + bridge.added() - bridge.removed() + bridge.existing() - bridge.pr()) < 0.01,
				"the change adds up: " + bridge);
		Path dir = Files.createTempDirectory("test-report");
		check(c.drawCharts(dir), "charts are drawn");
		for (String png : List.of("range.png", "bridge.png", "areas.png", "tests.png", "counts.png")) {
			check(Files.size(dir.resolve(png)) > 1000, "chart " + png);
		}
		String withCharts = c.timing("<!-- m -->", "https://raw.githubusercontent.com/o/r/b/pr-1/abc");
		check(withCharts.contains("![Tests that got slower or faster](https://raw.githubusercontent.com/o/r/b/pr-1/abc/tests.png)")
				&& withCharts.contains("<summary>Numbers</summary>"), "chart images with collapsed numbers");
		System.out.println("selftest passed");
	}

	private static String row(String markdown, String needle) {
		return Arrays.stream(markdown.split("\n")).filter(l -> l.contains(needle)).findFirst().orElse("");
	}

	private static Report report(String java, String commit, Map<String, Double> tests, Map<String, Double> classes,
			int order) {
		Map<String, Test> map = new TreeMap<>();
		tests.forEach((id, ms) -> map.put(id, new Test(id, "", 1, ms)));
		return new Report(java, commit, map, new TreeMap<>(classes), order);
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("selftest failed: " + what);
		}
	}

}
