package eu.dariolucia.jsatdecode.source.fmcomms2;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/* PRINT XML describes device IDs, channel order and scan format. XML is parsed
   only during setup. Permit libiio's internal DTD, but never resolve external
   DTDs/entities or fetch schemas. java.xml is required only when this source starts.
   Channel-mask bits are libiio channel numbers after scan-index/shift sorting,
   NOT necessarily raw scan indices or positions in the transmitted XML. */
final class IioContext {
    record Attribute(String name, String filename) {
    }

    record Channel(String id, boolean output, int index, String format, Map<String, Attribute> attributes) {
    }

    record Device(String id, String name, List<Channel> channels, Map<String, Attribute> attributes) {
        Channel channel(String id, boolean output) throws IOException {
            return channels.stream().filter(c -> c.id.equals(id) && c.output == output).findFirst()
                    .orElseThrow(() -> new IOException("Missing IIO channel " + this.id + " " + id + (output ? " OUTPUT" : " INPUT")));
        }

        String attribute(String name) throws IOException {
            if (!attributes.containsKey(name)) throw new IOException("Missing IIO device attribute " + id + " " + name);
            return id + " " + name;
        }

        String channelAttribute(String channel, boolean output, String name) throws IOException {
            Channel c = channel(channel, output);
            if (!c.attributes.containsKey(name))
                throw new IOException("Missing IIO channel attribute " + id + " " + channel + " " + name);
            return id + (output ? " OUTPUT " : " INPUT ") + channel + " " + name;
        }

        String rxAttribute(int receiver, String name) throws IOException {
            Channel selected = channel("voltage" + receiver, false);
            if (selected.attributes.containsKey(name)) return channelAttribute(selected.id, false, name);
            String shared = "in_voltage_" + name, individual = "in_voltage" + receiver + "_" + name;
            for (String candidate : List.of(individual, shared)) {
                if (attributes.containsKey(candidate)) return attribute(candidate);
                for (Channel c : channels)
                    if (!c.output) for (Attribute attr : c.attributes.values())
                        if (attr.filename.equals(candidate)) return channelAttribute(c.id, false, attr.name);
            }
            throw new IOException("Missing IIO RX attribute " + id + " " + name);
        }

        String firEnable() throws IOException {
            if (attributes.containsKey("in_out_voltage_filter_fir_en"))
                return attribute("in_out_voltage_filter_fir_en");
            return channelAttribute("out", false, "voltage_filter_fir_en");
        }

        Layout layout(int receiver) throws IOException {
            Channel i = channel("voltage" + (receiver * 2), false), q = channel("voltage" + (receiver * 2 + 1), false);
            if (i.index < 0 || q.index < 0 || i.index == q.index)
                throw new IOException("RX I/Q must have distinct scan indices");
            Format fi = Format.parse(i.format), fq = Format.parse(q.format);
            int[] mask = new int[(channels.size() + 31) / 32];
            mask[channels.indexOf(i) / 32] |= 1 << (channels.indexOf(i) % 32);
            mask[channels.indexOf(q) / 32] |= 1 << (channels.indexOf(q) % 32);
            StringBuilder text = new StringBuilder();
            for (int word = mask.length - 1; word >= 0; word--)
                text.append(String.format(Locale.ROOT, "%08x", mask[word]));
            return new Layout(text.toString(), i.index < q.index ? 0 : 2, i.index < q.index ? 2 : 0, fi, fq);
        }
    }

    record Layout(String mask, int iOffset, int qOffset, Format iFormat, Format qFormat) {
        void convert(byte[] bytes, int offset, int samples, float[] iq) {
            for (int n = 0; n < samples; n++, offset += 4) {
                iq[n * 2] = iFormat.value(bytes, offset + iOffset);
                iq[n * 2 + 1] = qFormat.value(bytes, offset + qOffset);
            }
        }
    }

    record Format(boolean bigEndian, boolean fullyDefined, int bits, int shift, float divisor) {
        private static final Pattern SYNTAX = Pattern.compile("(le|be):([sS])(\\d{1,2})/16(?:X(\\d+))?>>(\\d{1,2})");

        static Format parse(String text) throws IOException {
            var match = SYNTAX.matcher(text);
            if (!match.matches())
                throw new IOException("Unsupported IIO scan format (signed 16-bit scalar required): " + text);
            int bits = Integer.parseInt(match.group(3)), shift = Integer.parseInt(match.group(5));
            if (bits < 1 || bits + shift > 16 || (match.group(4) != null && !match.group(4).equals("1")))
                throw new IOException("Unsupported IIO width/shift/repeat: " + text);
            return new Format(match.group(1).equals("be"), match.group(2).equals("S") || bits == 16, bits, shift, Math.scalb(1.0f, bits - 1));
        }

        float value(byte[] bytes, int offset) {
            int raw = bigEndian ? ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255) : ((bytes[offset + 1] & 255) << 8) | (bytes[offset] & 255);
            int sample;
            if (fullyDefined) sample = ((short) raw) >> shift;
            else {
                sample = (raw >>> shift) & ((1 << bits) - 1);
                sample = (sample ^ (1 << (bits - 1))) - (1 << (bits - 1));
            }
            return sample / divisor;
        }
    }

    private final List<Device> devices;

    private IioContext(List<Device> devices) {
        this.devices = List.copyOf(devices);
    }

    Device device(String selector) throws IOException {
        List<Device> found = devices.stream().filter(d -> d.id.equals(selector) || d.name.equals(selector)).toList();
        if (found.size() != 1)
            throw new IOException("Expected one IIO device named/id " + selector + ", found " + found.size());
        return found.get(0);
    }

    static IioContext parse(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }
            });
            Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if (!root.getTagName().equals("context")) throw new IOException("IIOD PRINT is not an IIO context");
            List<Device> devices = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Element device : children(root, "device")) {
                String id = token(device.getAttribute("id"));
                if (!ids.add(id) || devices.size() == 256) throw new IOException("Duplicate/excess IIO devices");
                List<Channel> channels = new ArrayList<>();
                Set<String> channelIds = new HashSet<>();
                for (Element channel : children(device, "channel")) {
                    String cid = token(channel.getAttribute("id")), type = channel.getAttribute("type");
                    if (!Set.of("input", "output").contains(type) || !channelIds.add(type + ":" + cid) || channels.size() == 1024)
                        throw new IOException("Invalid/duplicate/excess IIO channels");
                    var scan = children(channel, "scan-element");
                    if (scan.size() > 1) throw new IOException("Multiple scan elements per IIO channel");
                    int index = scan.isEmpty() ? -1 : Integer.parseInt(scan.get(0).getAttribute("index"));
                    if (index < 0 && !scan.isEmpty()) throw new IOException("Negative scan index");
                    channels.add(new Channel(cid, type.equals("output"), index, scan.isEmpty() ? "" : scan.get(0).getAttribute("format"), attributes(channel)));
                }
                // Reproduce libiio's channel numbering, including shift order for packed fields.
                channels.sort(Comparator.comparingInt((Channel c) -> c.index < 0 ? Integer.MAX_VALUE : c.index)
                        .thenComparingInt(c -> c.index < 0 ? 0 : formatShift(c.format)));
                devices.add(new Device(id, device.getAttribute("name"), List.copyOf(channels), attributes(device)));
            }
            return new IioContext(devices);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid IIOD context XML", e);
        }
    }

    private static int formatShift(String format) {
        try {
            return Integer.parseInt(format.substring(format.lastIndexOf(">>") + 2));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static Map<String, Attribute> attributes(Element parent) throws IOException {
        Map<String, Attribute> result = new LinkedHashMap<>();
        for (Element element : children(parent, "attribute")) {
            String name = token(element.getAttribute("name"));
            if (result.put(name, new Attribute(name, element.getAttribute("filename"))) != null)
                throw new IOException("Duplicate IIO attribute");
        }
        return Map.copyOf(result);
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && element.getTagName().equals(tag)) result.add(element);
        return result;
    }

    private static String token(String text) throws IOException {
        if (!text.matches("[A-Za-z0-9_.:,-]{1,256}")) throw new IOException("Invalid IIOD command token in XML");
        return text;
    }
}
