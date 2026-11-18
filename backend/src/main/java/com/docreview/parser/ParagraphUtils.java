package com.docreview.parser;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 段落切分与标题识别的公共逻辑。
 */
public final class ParagraphUtils {

    private static final Pattern HEADING = Pattern.compile(
            "^(第[一二三四五六七八九十百零〇]+[章节部分]|[一二三四五六七八九十]+、|（[一二三四五六七八九十]+）|[0-9]+[、.]\\s+)");

    private ParagraphUtils() {
    }

    public static boolean isHeading(String text) {
        if (text.length() > 40) {
            return false;
        }
        return HEADING.matcher(text).find();
    }

    /**
     * 超长段落按句子切分，保证单个块不超过 maxChars。
     */
    public static void appendSplit(List<String> target, String text, int maxChars) {
        if (text.length() <= maxChars) {
            target.add(text);
            return;
        }
        StringBuilder buffer = new StringBuilder();
        for (String sentence : text.split("(?<=[。；;！？])")) {
            if (buffer.length() + sentence.length() > maxChars && buffer.length() > 0) {
                target.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            buffer.append(sentence);
        }
        if (buffer.length() > 0) {
            target.add(buffer.toString().trim());
        }
    }
}
