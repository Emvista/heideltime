package de.unihd.dbs.uima.annotator.heideltime.utilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Try to optimize constructed regexps for performance.
 * 
 * This is currently an ugly hack, and only supports a very limited subset of regular expressions.
 * 
 * In particular, only non-capturing groups are supported. It even has a hard-coded list (see method {@code #isSimple} of characters permitted.
 * 
 * Don't use it on regexps that expand massively, such as phone numbers!
 * 
 * This needs to eventually be rewritten into a more general tool, and with a proper regexp parser.
 * 
 * @author Erich Schubert
 */
public class RegexpOptimizer {
	/** Class logger */
	private static final Logger LOG = LoggerFactory.getLogger(RegexpOptimizer.class);

	/**
	 * Class when unsupported constructs are used in the optimizer.
	 * 
	 * @author Erich Schubert
	 */
	public static class OptimizerException extends Exception {
		/** Serialization version */
		private static final long serialVersionUID = 1L;

		/**
		 * Constructor.
		 *
		 * @param m
		 *                Error message.
		 */
		public OptimizerException(String m) {
			super(m);
		}
	}

	@FunctionalInterface
	public static interface Consumer {
		void accept(CharSequence str) throws OptimizerException;
	}

	public static void expandPatterns(String s, Consumer out) throws OptimizerException {
		expandPatterns(s, 0, s.length(), new StringBuilder(), out);
	}

	public static void expandPatterns(String s, int i, int len, StringBuilder b, Consumer out) throws OptimizerException {
		if (i >= len) {
			out.accept(b);
			return;
		}
		char cur = s.charAt(i);
		if (isSimple(cur)) {
			int l = b.length();
			b.append(cur);
			expandPatterns(s, i + 1, len, b, out);
			b.setLength(l);
			return;
		}
		if (cur == '\\') {
			// Escape character.
			if (i == len)
				throw new OptimizerException("Last character was an escape in");
			int l = b.length();
			b.append(cur).append(s.charAt(i + 1));
			expandPatterns(s, i + 2, len, b, out);
			b.setLength(l);
			return;
		}
		if (cur == '?') {
			// Previous character was optional.
			int l = b.length();
			if (l == 0)
				throw new OptimizerException("First character was a question mark");
			if (isSimple(b.charAt(l - 1)) && (l == 1 || isSimple(b.charAt(l - 2)))) {
				// Expand with
				expandPatterns(s, i + 1, len, b, out);
				b.setLength(l - 1);
				// Expand without.
				expandPatterns(s, i + 1, len, b, out);
				b.setLength(l - 1);
			} else {
				b.append('?');
				expandPatterns(s, i + 1, len, b, out);
				b.setLength(l - 1);
			}
			return;
		}
		if (cur == '[') {
			int end = i + 1, nextp = -1;
			boolean simple = true;
			String optional = null;
			for (; end < len; end++) {
				char next = s.charAt(end);
				if (next == ']') {
					nextp = end + 1;
					if (end + 1 < len && s.charAt(end + 1) == '?') {
						optional = "?";
						nextp++;
						// Possessive
						if (end + 2 < len && s.charAt(end + 2) == '+') {
							optional = "?+";
							nextp++;
						}
					}
					break;
				}
				if (next == '-')
					if (end != i + 1 && (end + 1 < len && s.charAt(end + 1) != ']'))
						simple = false;
				if (next == '[')
					throw new OptimizerException("Nested [");
				if (next == '\\')
					throw new OptimizerException("Escaped chars");
			}
			if (end >= len || s.charAt(end) != ']')
				throw new OptimizerException("Did not find matching []");
			final int l = b.length();
			if (simple) {
				// Expand simple character ranges:
				if (optional != null) {
					// FIXME: retain possessive?
					expandPatterns(s, nextp, len, b, out);
					b.setLength(l);
				}
				for (int j = i + 1; j < end; j++) {
					char c = s.charAt(j);
					if (c == '.')
						b.append('\\');
					b.append(c);
					expandPatterns(s, nextp, len, b, out);
					b.setLength(l);
				}
			} else {
				assert (s.charAt(i) == '[' && s.charAt(end) == ']') : s.substring(i, nextp);
				if (end - i < 4) {
					// System.err.println("*****X " + s.substring(i, nextp) + " " + optional);
				} else if (end - i == 4) {
					// System.err.println("****** " + s.substring(i, nextp) + " " + optional);
					char c1 = s.charAt(i + 1), c2 = s.charAt(i + 2), c3 = s.charAt(i + 3);
					// Expand small ranges
					if (c2 == '-' && isSimple(c1) && isSimple(c3) && c1 < c3 && c3 - c1 < 10) {
						if (optional != null) {
							// FIXME: retain possessive?
							expandPatterns(s, nextp, len, b, out);
							b.setLength(l);
						}
						for (char c = c1; c <= c3; c++) {
							if (!isSimple(c)) {
								throw new OptimizerException("Non-simple char in char range: " + c);
							}
							b.append(c);
							expandPatterns(s, nextp, len, b, out);
							b.setLength(l);
						}
					}
					return;
				}
				// We simply copy&paste more complex character ranges
				for (int j = i; j < nextp; j++)
					b.append(s.charAt(j));
				expandPatterns(s, nextp, len, b, out);
				b.setLength(l);
			}
			return;
		}
		if (cur == '(') {
			int end = i + 1, begin = i + 1, nextp = -1;
			int depth = 1;
			boolean simple = true;
			String optional = null;
			for (int r = 0; end < len; end++, r++) {
				char next = s.charAt(end);
				if (r == 0) {
					if (next != '?')
						throw new OptimizerException("Non-optional group");
					++begin;
					continue;
				}
				if (r == 1) {
					if (next != ':')
						throw new OptimizerException("Non-optional group");
					++begin;
					continue;
				}
				if (next == ')' && --depth == 0) {
					nextp = end + 1;
					// Trailing modifiers
					if (end + 1 < len && s.charAt(end + 1) == '?') {
						optional = "?";
						nextp++;
						// Possessive
						if (end + 2 < len && s.charAt(end + 2) == '+') {
							optional = "?+";
							nextp++;
						}
					}
					break;
				}
				if (next == '\\') {
					simple = false;
					if (end + 1 == len)
						throw new OptimizerException("Escape at end of group?!?");
					++end;
				}
				if (next == '[' || next == '?' || next == '*' || next == '\\') {
					simple = false;
					// throw new ExpansionException("Special char " + next + " in group");
				}
				if (next == '(') {
					++depth;
					simple = false;
				}
			}
			if (end >= len || s.charAt(end) != ')')
				throw new OptimizerException("Did not find matching '()'");
			if (simple) {
				int l = b.length();
				if (optional != null) {
					expandPatterns(s, nextp, len, b, out);
					b.setLength(l);
				}
				for (int j = begin; j < end; j++) {
					char c = s.charAt(j);
					if (c == '|') {
						expandPatterns(s, nextp, len, b, out);
						b.setLength(l);
						continue;
					}
					b.append(c);
				}
				expandPatterns(s, nextp, len, b, out);
				b.setLength(l);
				return;
			}
			// Non-simple expansion:
			// LOG.trace("Need to expand: " + s.substring(begin - 3, begin) + ">>" + s.substring(begin, end) + "<<" + s.substring(end, nextp));
			assert (depth == 0);
			depth = 0;
			int l = b.length();
			if (optional != null) {
				expandPatterns(s, nextp, len, b, out);
				b.setLength(l);
			}
			final int cont = nextp; // Make effectively final.
			int prev = begin;
			for (int j = begin; j < end; j++) {
				char c = s.charAt(j);
				if (c == '|' && depth == 0) {
					// LOG.trace("Need to expand: " + s.substring(prev, j));
					expandPatterns(s, prev, j, new StringBuilder(), x -> {
						// LOG.trace("Recursive expansion to: " + x);
						b.append(x);
						expandPatterns(s, cont, len, b, out);
						b.setLength(l);
					});
					prev = j + 1;
				} else if (c == '(')
					++depth;
				else if (c == ')')
					--depth;
				else if (c == '\\')
					++j;
			}
			if (depth != 0)
				throw new OptimizerException("Could not close () group.");
			expandPatterns(s, prev, end, new StringBuilder(), x -> {
				// System.err.println("Recursive expansion to: " + x);
				b.append(x);
				expandPatterns(s, cont, len, b, out);
				b.setLength(l);
			});
			return;
		}
		throw new OptimizerException("Unhandled character " + cur + " at " + s.substring(Math.max(0, i - 5), Math.min(s.length(), i + 5)));
	}

	private static boolean isSimple(char cur) {
		return cur == ' ' || cur == '\'' || cur == '&' || cur == '-' || cur == ',' || Character.isAlphabetic(cur) || Character.isDigit(cur);
	}

	private static final Comparator<String> upperLowerChar = new Comparator<String>() {
		public int compare(String o1, String o2) {
			int l1 = o1.length(),l2=o2.length();int l=l1<l2?l1:l2;for(int i=0;i<l;i++){char c1=o1.charAt(i),c2=o2.charAt(i);if(c1!=c2){char d1=Character.toLowerCase(c1),d2=Character.toLowerCase(c2);return(d1==d2)?Character.compare(c1,c2):Character.compare(d1,d2);}}return l1<l2?-1:l1==l2?0:+1;}};

	public static String combinePatterns(Collection<String> patterns) throws OptimizerException {
		String[] ps = patterns.toArray(new String[patterns.size()]);
		Arrays.sort(ps, upperLowerChar);
		// if (ps.length < 100) System.err.println(String.join("|", ps));
		ArrayList<String> toplevel = new ArrayList<>();
		build(ps, 0, ps.length, 0, x -> toplevel.add(x.toString()));
		StringBuilder buf = new StringBuilder();
		buildGroup(toplevel.toArray(new String[toplevel.size()]), 0, toplevel.size(), 0, 0, x -> {
			assert (buf.length() == 0);
			buf.append(x);
		}, new StringBuilder(), new StringBuilder());
		return buf.toString();
	}

	private static void build(String[] ps, int start, int end, int knownl, Consumer out) throws OptimizerException {
		String k = ps[start];
		// Only one string remaining:
		if (start + 1 == end) {
			if (knownl == k.length()) {
				out.accept("");
				return;
			}
			char next = k.charAt(knownl);
			if (next == '*' || next == '?' || next == '+') {
				throw new OptimizerException("Bad split: " + k.substring(0, knownl) + "<<>>" + k.substring(knownl));
			}
			out.accept(k.substring(knownl));
			return;
		}
		int l = nextLength(k, knownl);
		// System.err.println("Next length: " + l + " in " + k);
		StringBuilder buf1 = new StringBuilder(), buf2 = new StringBuilder();
		int begin = start, pos = start;
		while (pos < end) {
			String cand = ps[pos];
			if (k.regionMatches(0, cand, 0, l)) {
				++pos;
				continue;
			}
			buildGroup(ps, begin, pos, knownl, l, out, buf1, buf2);
			k = cand;
			begin = pos;
			l = nextLength(k, knownl);
			// System.err.println("Next length: " + l + " in " + k);
		}
		if (begin < pos) {
			buildGroup(ps, begin, pos, knownl, l, out, buf1, buf2);
		}
	}

	private static void buildGroup(String[] ps, int begin, int end, int subbegin, int subend, Consumer out, StringBuilder buf, StringBuilder tmp) throws OptimizerException {
		String key = ps[begin];
		// One element "group":
		if (begin + 1 == end) {
			buf.setLength(0);
			buf.append(key, subbegin, key.length());
			out.accept(buf);
			return;
		}
		// Skip a prefix if shared by all strings:
		assert (subend <= key.length()) : key + " " + subbegin + "-" + subend;
		// p is the first position where they differ.
		int p = prefixLength(ps, begin, end, subend);
		// Exact match:
		boolean prefixOnly = false;
		if (key.length() == p) {
			// Only one more entry remaining.
			if (begin + 2 == end) {
				String other = ps[begin + 1];
				int postlen = other.length() - p;
				assert (postlen > 0);
				buf.setLength(0);
				if (postlen == 1) {
					buf.append(other, subbegin, other.length()).append('?');
				} else {
					buf.append(other, subbegin, p);
					buf.append("(?:");
					buf.append(other, p, other.length());
					buf.append(")?");
				}
				out.accept(buf);
				return;
			}
			prefixOnly = true;
			++begin;
			p = prefixLength(ps, begin, end, p);
			key = ps[begin];
		}
		ArrayList<String> cs = new ArrayList<>();
		build(ps, begin, end, p, x -> cs.add(x.toString()));
		String postfix = findPostfix(cs);
		// Check if we have an entry "PrefixPostfix":
		boolean preandpostfixOnly = !postfix.isEmpty() && cs.remove(postfix);
		// All remaining are char+postfix, and can thus be converted to a character range
		if (sameLength(cs, 1 + postfix.length())) {
			assert (cs.size() > 0);
			// Build character range (if more than one character:
			tmp.setLength(0);
			if (cs.size() > 1) {
				tmp.append('[');
				for (int i = 0; i < cs.size(); i++) {
					tmp.append(cs.get(i).charAt(0));
				}
				mergeCharRanges(tmp, 1); // Ignoring "["
				tmp.append(']');
			} else {
				tmp.append(cs.get(0).charAt(0));
			}
			// Prefix:
			buf.setLength(0);
			buf.append(key, subbegin, p);
			if (prefixOnly) {
				if (!postfix.isEmpty()) {
					// Will look like "pre(?:[a-z]?post)?"
					buf.append("(?:").append(tmp);
					if (preandpostfixOnly) {
						buf.append('?');
					}
					buf.append(postfix).append(")?"); // prefixOnly
				} else {
					// Will look like "pre[a-z]?
					buf.append(tmp).append('?'); // prefixOnly
				}
			} else {
				// Will look like "pre[a-z]?post"
				buf.append(tmp);
				if (preandpostfixOnly) {
					buf.append('?');
				}
				buf.append(postfix);
			}
			// System.err.println(buf);
			out.accept(buf);
			return;
		}
		// Main case, build a group.
		// prefix(?:(?:alt|ern|ati|ves)?postfix)?
		buf.setLength(0);
		buf.append(key, subbegin, p);
		// Remember the current position (to undo "(?:" below)
		final int oldpos = buf.length();
		// We need double brackets if we have an optional non-empty postfix:
		buf.append(prefixOnly && preandpostfixOnly ? "(?:(?:" : "(?:");

		// Merge subsequent alternatives with a common postfix except the first char into char ranges:
		int alternatives = 0;
		boolean cansimplify = false;
		for (int i = 0; i < cs.size(); i++) {
			String wi = cs.get(i);
			if (wi == null) {
				continue;
			}
			assert (wi.length() > 0);
			// Collect the letters if the postfix matches:
			tmp.setLength(0);
			tmp.append(wi.charAt(0));
			for (int j = i + 1; j < cs.size(); j++) {
				String wj = cs.get(j);
				if (wj == null) {
					continue;
				}
				char cj = wj.charAt(0);
				if (isSimple(cj) && wi.regionMatches(1, wj, 1, wi.length() - 1)) {
					tmp.append(cj);
					cs.set(j, null);
				}
			}
			// Separate alternatives
			if (alternatives++ > 0) {
				buf.append('|');
			}
			if (tmp.length() > 1) {
				cansimplify = (alternatives == 1); // If we have exactly one char-range.
				mergeCharRanges(tmp, 0);
				buf.append('[').append(tmp).append(']');
				buf.append(wi, 1, wi.length() - postfix.length());
			} else {
				buf.append(wi, 0, wi.length() - postfix.length());
			}
		}
		if (cansimplify && alternatives == 1 && !preandpostfixOnly) {
			// We want to simplify: pre(?:[a-z]mid)?post -> pre[a-z]?midpost
			assert (buf.charAt(oldpos) == '(') : buf;
			buf.delete(oldpos, oldpos + 3); // Remove one "(?:"
			assert (buf.charAt(oldpos) == '[') : buf;
			assert (buf.charAt(oldpos + tmp.length() + 1) == ']') : buf;
			// At this point, our pattern looks like this:
			// pre[0-9]mid
			buf.append(postfix);
			out.accept(buf);
			return;
		}
		// At this point, our pattern looks like this:
		if (preandpostfixOnly) {
			// prefix(?:ab|cd or prefix(?:(?:ab|cd
			buf.append(")?");
		} else if (!prefixOnly) {
			// non-optional case: a(?:b|c)d
			buf.append(')');
		}
		buf.append(postfix);
		if (prefixOnly) {
			// prefix(?:(?:ab|cd)[?]post)?
			buf.append(")?");
		}
		out.accept(buf);
	}

	/**
	 * Check if all strings have the same length.
	 *
	 * @param cs
	 *                Collection
	 * @param len
	 *                Required length
	 * @return {@code true} if all have the same length.
	 */
	private static boolean sameLength(Collection<String> cs, int len) {
		for (String s : cs) {
			if (s.length() != len) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Merge subsequent character ranges, if more than 2.
	 * 
	 * E.g. convert "01234" -> "0-4"
	 * 
	 * @param chars
	 *                Character buffer
	 * @param start
	 *                Starting position (set to 1 if you already have '[' in the buffer)
	 */
	private static void mergeCharRanges(StringBuilder chars, int start) {
		// Build ranges:
		for (int i = start; i < chars.length();) {
			char c = chars.charAt(i);
			int j = i + 1;
			while (j < chars.length() && chars.charAt(j) == ++c && isSimple(chars.charAt(j))) {
				++j;
			}
			if (j - i >= 3) {
				chars.replace(i, j, chars.charAt(i) + "-" + chars.charAt(j - 1));
				i += 2;
			} else {
				i = j;
			}
		}
	}

	private static String findPostfix(ArrayList<String> cs) {
		final String first = cs.get(0);
		final int num = cs.size();
		int l = 1, p = first.length() - 1, good = 0, level = 0;
		outer: while (p >= 0) {
			char c = first.charAt(p);
			char prev = p > 0 ? first.charAt(p - 1) : 'X';
			for (int i = 1; i < num; i++) {
				String cand = cs.get(i);
				if (cand.length() < l || cand.charAt(cand.length() - l) != c) {
					break outer;
				}
			}
			if (prev != '\\' && (c == '[' || c == '(')) {
				--level;
			}
			good = (level != 0 || !isSimple(c) || prev == '\\') ? good : l;
			if (prev != '\\' && (c == ']' || c == ')')) {
				++level;
			}
			if (prev == '\\' && c == '\\') {
				break; // Too complex, there could be more.
			}
			++l;
			--p;
		}
		return good > 0 ? first.substring(first.length() - good) : "";
	}

	private static int nextLength(String k, int p) throws OptimizerException {
		int l = p;
		assert (l < k.length()) : "Trying to access char " + l + " of: " + k;
		char next = k.charAt(l);
		if (next == '\\') {
			if (k.length() == l) {
				throw new OptimizerException("Trailing backslash? " + k);
			}
			++l;
		}
		++l;
		while (l < k.length()) {
			char next2 = k.charAt(l);
			if (next2 == '?' || next2 == '*' || next2 == '+') {
				++l;
			} else {
				break;
			}
		}
		return l;
	}

	/**
	 * Find the length of a shared prefix.
	 * 
	 * @param ps
	 *                Data array
	 * @param start
	 *                Subset begin
	 * @param end
	 *                Subset end
	 * @param p
	 *                Known prefix length
	 * @return New prefix length
	 */
	private static int prefixLength(String[] ps, int start, int end, int p) {
		final String k = ps[start];
		if (p == k.length()) {
			return p;
		}
		int good = p;
		int inset = 0;
		char prev = p > 0 ? k.charAt(p - 1) : 'X';
		char next = k.charAt(p);
		common: while (p < k.length()) {
			for (int i = start + 1; i < end; i++) {
				String cand = ps[i];
				if (cand.length() < p || cand.charAt(p) != next) {
					break common;
				}
			}
			if (prev == '\\') {
				prev = 'X';
			} else {
				if (next == '[') {
					++inset;
				} else if (next == ']') {
					--inset;
				}
				prev = next;
			}
			++p;
			next = p < k.length() ? k.charAt(p) : 'X';
			good = (inset > 0 || prev == '\\' || next == '?' || next == '*' || next == '+') ? good : p;
		}
		return good;
	}

	public static void main(String[] args) {
		List<String> expanded = new ArrayList<>();
		try {
			String[] test = { "1(?:st|\\.)? Advent", "first Advent", //
					"2(?:nd|\\.)? Advent", "second Advent", //
					"3(?:rd|\\.)? Advent", "third Advent", //
					"4(?:th|\\.)? Advent", "fourth Advent" };
			for (String s : test) {
				expandPatterns(s, x -> expanded.add(x.toString()));
			}
			for (String s : expanded) {
				System.out.println(s);
			}
			System.out.println(combinePatterns(expanded));
		} catch (OptimizerException e) {
			LOG.error(e.getMessage(), e);
		}
	}
}