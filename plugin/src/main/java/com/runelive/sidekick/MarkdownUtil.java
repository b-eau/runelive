package com.runelive.sidekick;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts a subset of Markdown to HTML for {@link javax.swing.JEditorPane}. */
public final class MarkdownUtil
{
	private static final Pattern HEADER = Pattern.compile("^(#{1,3}) (.+)");
	private static final Pattern UL_ITEM = Pattern.compile("^[-*+] (.*)");
	private static final Pattern OL_ITEM = Pattern.compile("^\\d+\\. (.*)");
	private static final Pattern HR = Pattern.compile("^[-*_]{3,}\\s*$");

	private MarkdownUtil()
	{
	}

	public static String toHtml(String markdown)
	{
		if (markdown == null || markdown.isEmpty())
		{
			return "";
		}

		StringBuilder sb = new StringBuilder();
		boolean inUl = false;
		boolean inOl = false;

		for (String raw : markdown.split("\n", -1))
		{
			Matcher h = HEADER.matcher(raw);
			Matcher ul = UL_ITEM.matcher(raw);
			Matcher ol = OL_ITEM.matcher(raw);
			Matcher hr = HR.matcher(raw);

			boolean isUl = ul.matches();
			boolean isOl = ol.matches();

			if (!isUl && inUl)
			{
				sb.append("</ul>");
				inUl = false;
			}
			if (!isOl && inOl)
			{
				sb.append("</ol>");
				inOl = false;
			}

			if (h.matches())
			{
				int level = h.group(1).length();
				sb.append("<h").append(level).append('>')
					.append(inline(esc(h.group(2))))
					.append("</h").append(level).append('>');
			}
			else if (isUl)
			{
				if (!inUl)
				{
					sb.append("<ul>");
					inUl = true;
				}
				sb.append("<li>").append(inline(esc(ul.group(1)))).append("</li>");
			}
			else if (isOl)
			{
				if (!inOl)
				{
					sb.append("<ol>");
					inOl = true;
				}
				sb.append("<li>").append(inline(esc(ol.group(1)))).append("</li>");
			}
			else if (hr.matches())
			{
				sb.append("<hr>");
			}
			else if (raw.trim().isEmpty())
			{
				sb.append("<br>");
			}
			else
			{
				sb.append("<p>").append(inline(esc(raw))).append("</p>");
			}
		}

		if (inUl)
		{
			sb.append("</ul>");
		}
		if (inOl)
		{
			sb.append("</ol>");
		}
		return sb.toString();
	}

	private static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Applies bold, italic, inline-code, and link formatting. */
	private static String inline(String s)
	{
		// Bold must be matched before italic so ** isn't consumed as two *s
		s = s.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
		s = s.replaceAll("__(.+?)__", "<b>$1</b>");
		s = s.replaceAll("\\*([^*<>]+?)\\*", "<i>$1</i>");
		s = s.replaceAll("`([^`]+?)`", "<code>$1</code>");
		s = s.replaceAll("\\[([^\\]]+)]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
		return s;
	}
}
