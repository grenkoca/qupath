/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2022 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.gui.scripting.richtextfx.stylers;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.common.LogTools;

/**
 * Styling to apply to a {@link CodeArea}, based on Python syntax.
 * @author Pete Bankhead
 * @since v0.4.0
 */
public class PythonStyler implements ScriptStyler {
	
	private static final Logger logger = LoggerFactory.getLogger(PythonStyler.class);
	
	/**
	 * Can be generated from Python with
	 * <pre>{@code 
	 * import keyword
	 * print(", ".join([f'"{kw}"' for kw in sorted(keyword.kwlist)]))
	 * }</pre>
	 */
	private static final String[] KEYWORDS = new String[] {
			"False", "None", "True", "and", "as", "assert", "async", 
			"await", "break", "class", "continue", "def", "del", 
			"elif", "else", "except", "finally", "for", "from",
			"global", "if", "import", "in", "is", "lambda", 
			"nonlocal", "not", "or", "pass", "raise", 
			"return", "try", "while", "with", "yield"
    };
	
	private static Pattern PATTERN;
	
	static {
		final String KEYWORD_PATTERN = "\\b(" + String.join("|", KEYWORDS) + ")\\b";
	    final String PAREN_PATTERN = "\\(|\\)";
	    final String BRACE_PATTERN = "\\{|\\}";
	    final String BRACKET_PATTERN = "\\[|\\]";
	    final String SEMICOLON_PATTERN = "\\;";
	    final String TRIPLE_QUOTE_PATTERN = "\"\"\"([^\"\"\"\\\\]|\\\\.)*\"\"\"";
	    final String DOUBLE_QUOTE_PATTERN = "\"([^\"\\\\]|\\\\.)*\"";
	    final String SINGLE_QUOTE_PATTERN = "'([^'\\\\]|\\\\.)*\'";
	    final String COMMENT_PATTERN = "#[^\n]*";
	    
	    PATTERN = Pattern.compile(
	            "(?<KEYWORD>" + KEYWORD_PATTERN + ")"
	            + "|(?<PAREN>" + PAREN_PATTERN + ")"
	            + "|(?<BRACE>" + BRACE_PATTERN + ")"
	            + "|(?<BRACKET>" + BRACKET_PATTERN + ")"
	            + "|(?<SEMICOLON>" + SEMICOLON_PATTERN + ")"
	            + "|(?<TRIPLEQUOTES>" + TRIPLE_QUOTE_PATTERN + ")"
	            + "|(?<DOUBLEQUOTES>" + DOUBLE_QUOTE_PATTERN + ")"
	            + "|(?<SINGLEQUOTES>" + SINGLE_QUOTE_PATTERN + ")"
	            + "|(?<COMMENT>" + COMMENT_PATTERN + ")"
	    );
	    
	}
	
	/**
	 * Constructor.
	 */
	PythonStyler() {}
	
	@Override
	public Set<String> getLanguageNames() {
		return Set.of("python", "jython", "cpython", "python py4j", "graalpy");
	}
	
	@Override
	public StyleSpans<Collection<String>> computeEditorStyles(final String text) {
		
		// Stop quickly if we have really long lines, which can cause QuPath to freeze
		int longLine = ScriptStylerTools.DEFAULT_LONG_LINE_LENGTH;
		if (ScriptStylerTools.containsLongLines(text, longLine)) {
			LogTools.logOnce(logger, "Text contains lines longer than " + longLine + " - no styling will be applied");
			return ScriptStylerProvider.getPlainStyling(text);
		}
		
        Matcher matcher = PATTERN.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        while (matcher.find()) {
            String styleClass =
                    matcher.group("KEYWORD") != null ? "keyword" :
                    matcher.group("PAREN") != null ? "paren" :
                    matcher.group("BRACE") != null ? "brace" :
                    matcher.group("BRACKET") != null ? "bracket" :
                    matcher.group("SEMICOLON") != null ? "semicolon" :
                    matcher.group("TRIPLEQUOTES") != null ? "string" :
                    matcher.group("DOUBLEQUOTES") != null ? "string" :
                    matcher.group("SINGLEQUOTES") != null ? "string" :
                    matcher.group("COMMENT") != null ? "comment" :
                    null; /* never happens */
            assert styleClass != null;
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
            spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
        return spansBuilder.create();
    }
	
	@Override
	public StyleSpans<Collection<String>> computeConsoleStyles(final String text, boolean logConsole) {
		return ScriptStylerProvider.getLogStyling(text);
	}
	
}
