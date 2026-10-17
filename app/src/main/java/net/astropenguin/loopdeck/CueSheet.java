package net.astropenguin.loopdeck;

import java.util.*;
import java.util.regex.*;

/** INDEX 01 defines track starts; INDEX 00 remains with the preceding track. */
final class CueSheet {
    static final class Part {
        final String file, title;
        final int number;
        final long start, end;
        Part(String file,String title,int number,long start,long end) {
            this.file=file; this.title=title; this.number=number; this.start=start; this.end=end;
        }
    }
    private static final Pattern FILE=Pattern.compile("^FILE\\s+(?:\"([^\"]+)\"|(\\S+))\\s+\\S+\\s*$",Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACK=Pattern.compile("^TRACK\\s+(\\d+)\\s+(\\S+)\\s*$",Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX=Pattern.compile("^INDEX\\s+01\\s+(\\d+):(\\d{2}):(\\d{2})\\s*$",Pattern.CASE_INSENSITIVE);
    static List<Part> parse(String text) {
        ArrayList<Part> raw=new ArrayList<>();
        String file="", title=""; int number=0; boolean audio=false, indexed=false;
        for (String line:text.replace("\ufeff","").split("\\r?\\n")) {
            line=line.trim(); Matcher f=FILE.matcher(line), t=TRACK.matcher(line), i=INDEX.matcher(line);
            if (f.matches()) { file=f.group(1)!=null ? f.group(1) : f.group(2); audio=false; }
            else if (t.matches()) {
                try { number=Integer.parseInt(t.group(1)); } catch (NumberFormatException e) { return Collections.emptyList(); }
                audio=t.group(2).equalsIgnoreCase("AUDIO"); title=""; indexed=false;
            } else if (audio && line.matches("(?i)^TITLE\\s+.*")) {
                title=line.substring(5).trim();
                if (title.startsWith("\"") && title.endsWith("\"") && title.length()>=2) title=title.substring(1,title.length()-1);
                if (indexed) {
                    Part current=raw.get(raw.size()-1);
                    raw.set(raw.size()-1,new Part(current.file,title.isEmpty() ? "Track "+number : title,number,current.start,-1));
                }
            } else if (audio && i.matches()) {
                if (indexed) return Collections.emptyList();
                try {
                    long minutes=Long.parseLong(i.group(1)); int seconds=Integer.parseInt(i.group(2)), frames=Integer.parseInt(i.group(3));
                    if (seconds>=60 || frames>=75 || minutes>1000000) return Collections.emptyList();
                    raw.add(new Part(file,title.isEmpty() ? "Track "+number : title,number,(minutes*60+seconds)*1000+frames*1000/75,-1)); indexed=true;
                } catch (NumberFormatException e) { return Collections.emptyList(); }
            }
        }
        ArrayList<Part> result=new ArrayList<>();
        Map<String,Long> last=new HashMap<>();
        for (int n=raw.size()-1;n>=0;n--) {
            Part part=raw.get(n); String key=part.file.toLowerCase(Locale.ROOT);
            long end=last.getOrDefault(key,-1L);
            if (end>=0 && end<=part.start) return Collections.emptyList();
            result.add(new Part(part.file,part.title,part.number,part.start,end)); last.put(key,part.start);
        }
        Collections.reverse(result); return result;
    }
    private CueSheet() {}
}
