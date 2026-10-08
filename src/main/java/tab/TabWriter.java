package tab;

import model.Instrument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class TabWriter {
    private StyleSettings style;
    protected StringBuilder tab = new StringBuilder();

    public TabWriter(StyleSettings style) {
        this.style = style;
    }

    public void writeTitle(String title) {
        tab.append("<html>\n<body>\n<meta charset=\"utf-8\"><b style=\"font-weight:normal;\">");
        tab.append("<p dir=\"ltr\" style=\"line-height:1.38;margin-top:0pt;margin-bottom:0pt;\">");
        tab.append("<span style=\"font-size:20pt;font-family:Arial,sans-serif;font-weight:700;font-style:normal;text-decoration:none;white-space:pre;")
                .append(textColor(style.titleColor, style.titleColorEnabled)).append("\">");
        tab.append("\t").append(title).append("</span></p>");
    }

    public void writeHeader(ArrayList<Instrument> instruments, int numBoxes) {
        tab.append("<tr>");
        tab.append("<td style=\"").append(cellStyler(style.borderTopColor, style.borderTopEnabled));
        tab.append("<p dir=\"ltr\" style=\"line-height:1.2;margin-top:0pt;margin-bottom:0pt;\">");
        tab.append("<span style=\"font-size:12pt;font-family:Georgia;font-weight:700;font-style:normal;text-decoration:none;\">&nbsp;");
        tab.append("</span></p></td>");
        Map<String, Integer> instrumentCounter = new HashMap<>();
        for (Instrument instrument : instruments) {
            instrumentCounter.merge(instrument.getName(), 1, Integer::sum);
        }
        Map<String, Integer> labelCounter = new HashMap<>();
        for (Instrument instrument : instruments) {
            labelCounter.merge(instrument.getName(), 1, Integer::sum);
            tab.append("<td style=\"").append(cellStyler(style.borderTopColor, style.borderTopEnabled));
            tab.append("<p dir=\"ltr\" style=\"line-height:1.2;margin-top:0pt;margin-bottom:0pt;\">");
            tab.append("<span style=\"font-size:12pt;font-family:Arial,sans-serif;font-weight:700;font-style:normal;text-decoration:none;")
                    .append(textColor(style.instrumentColor, style.instrumentColorEnabled)).append("\">");
            if (instrumentCounter.get(instrument.getName()) > 1) {
                tab.append(instrument.getName()).append(" ").append(labelCounter.get(instrument.getName()));
            } else {
                tab.append(instrument.getName());
            }
            tab.append("</span></p></td>");
        }
        tab.append("<td style=\"").append(cellStyler(style.borderTopColor, style.borderTopEnabled));
        tab.append("<p dir=\"ltr\" style=\"line-height:1.2;margin-top:0pt;margin-bottom:0pt;\">");
        tab.append("<span style=\"font-size:12pt;font-family:Arial,sans-serif;font-weight:700;font-style:normal;text-decoration:none;\">&nbsp;");
        tab.append("</span></p></td>");
        tab.append("</tr>");
    }

    public void writeRowLabel(int numBoxes, int row, char label) {
        tab.append("<td style=\"").append(cellStyler(style.borderSidesColor, style.borderSidesEnabled));
        tab.append("<p dir=\"ltr\" style=\"line-height:1.2;margin-top:0pt;margin-bottom:0pt;\">");
        tab.append("<span style=\"font-size:10pt;font-family:Georgia;font-weight:700;font-style:normal;text-decoration:none;")
                .append(textColor(style.boxLetterColor, style.boxLetterColorEnabled)).append("\">");
        tab.append(label).append("</span></p></td>");
    }

    public void writeBoxOpener(int numBoxes, int row) {
        tab.append("<td style=\"");
        tab.append(cellStyler(style.boxColor, style.boxColorEnabled));
    }

    public void writeLineOpener() {
        tab.append("<p dir=\"ltr\" style=\"line-height:1.2;margin-top:0pt;margin-bottom:0pt;\">");
        tab.append("<span style=\"font-size:10pt;font-family:Arial,sans-serif;font-weight:normal;font-style:normal;text-decoration:none;\">");
    }


    public void writeTableCloser(ArrayList<Instrument> instruments) {
        String colgroup = "";
        if (style.borderStyle.equals("Box")) {
            tab.append("<tr>");
            tab.append("<td style=\"").append(cellStyler(style.borderBottomColor, style.borderBottomEnabled));
            tab.append("<p dir=\"ltr\" style=\"line-height:1.2;margin-top:0pt;margin-bottom:0pt;\">");
            tab.append("<span style=\"font-size:12pt;font-family:Georgia;font-weight:700;font-style:normal;text-decoration:none;\">&nbsp;");
            tab.append("</span></p></td>");

            tab.append("<td colspan=\"").append(instruments.size()).append("\" style=\"").append(cellStyler(style.borderBottomColor, style.borderBottomEnabled));
            tab.append("<p dir=\"ltr\" style=\"line-height:1.2;margin-top:0pt;margin-bottom:0pt;\">");
            tab.append("<span style=\"font-size:12pt;font-family:Arial,sans-serif;font-weight:700;font-style:normal;text-decoration:none;color:white;\">&nbsp;");
            tab.append("</span></p></td>");

            tab.append("<td style=\"").append(cellStyler(style.borderBottomColor, style.borderBottomEnabled));
            tab.append("<p dir=\"ltr\" style=\"line-height:1.2;margin-top:0pt;margin-bottom:0pt;\">");
            tab.append("<span style=\"font-size:12pt;font-family:Georgia;font-weight:700;font-style:normal;text-decoration:none;\">&nbsp;");
            tab.append("</span></p></td>");
            tab.append("</tr>");

            colgroup = "<colgroup>" + getColumnWidths(instruments) + "</colgroup>";
        }
        tab.append("</tbody>");

        // set total table width
        double tableWidth = 40;
        for (Instrument instrument : instruments) {
            tableWidth += (instrument.getLongestRow());
        }

        // Column widths are only known now, so set the table width and insert the colgroup
        // (which must come before <tbody>) right after the opening table tag
        int tableIndex = tab.indexOf("<table");
        if (tableIndex != -1) {
            int endIndex = tab.indexOf(">", tableIndex);
            if (endIndex != -1) {
                tab.insert(endIndex + 1, colgroup);
                tab.insert(endIndex, " width=\"" + Math.round(tableWidth) + "\"");
            }
        }
        // Close table tags
        tab.append("</table></div>");

        if (style.closingTextEnabled) {
            tab.append("<span style=\"font-size:10pt;font-family:Arial,sans-serif;font-weight:400;font-style:normal;text-decoration:none;white-space:pre;\">");
            tab.append(style.closingText);
            tab.append("</span>");
        }
        tab.append("</b>\n</body>\n</html>");
    }

    public void write(String text) { tab.append(text); }

    public String getTab() { return tab.toString(); }

    public String writePrimaryBeatOpener() {
        if (style.measureHighlight.equals("Strong Beats") || style.measureHighlight.equals("First Beat")) {
            return "<b><mark style=\"background:" + style.highlightColor1 +";\">";
        } else {
            return "<b>";
        }
    }

    public String writePrimaryBeatCloser() {
        if (style.measureHighlight.equals("Strong Beats") || style.measureHighlight.equals("First Beat")) {
            return "</mark></b>";
        } else {
            return "</b>";
        }
    }

    public String writeSecondaryBeatOpener() {
        if (style.measureHighlight.equals("Strong Beats")) {
            return "<b><mark style=\"background:" + style.highlightColor2 +";\">";
        } else {
            return "";
        }
    }

    public String writeSecondaryBeatCloser() {
        if (style.measureHighlight.equals("Strong Beats")) {
            return "</mark></b>";
        } else {
            return "";
        }
    }

    public String getColumnWidths(ArrayList<Instrument> instruments) {
        StringBuilder cols = new StringBuilder();
        cols.append("<col width=\"").append(20).append("\" />");
        for (Instrument instrument : instruments) {
            cols.append("<col width=\"").append(Math.round(instrument.getLongestRow())).append("\" />");
        }
        cols.append("<col width=\"").append(20).append("\" />");
        return cols.toString();
    }

    public String cellStyler(String color, boolean isColored) {
        StringBuilder styleString = new StringBuilder();
        styleString.append("border-top:solid 1pt;");
        styleString.append("border-bottom:solid 1pt;");
        styleString.append("border-left:solid 1pt;");
        styleString.append("border-right:solid 1pt;");
        if(isColored) {
            styleString.append("background-color:").append(color).append(";");
        }
        styleString.append("padding:5pt 5pt 5pt 5pt;\">");

        return styleString.toString();
    }

    // Text colour for a span's style, or nothing so it keeps the normal colour
    private String textColor(String color, boolean isColored) {
        return isColored ? "color:" + color + ";" : "";
    }
}
