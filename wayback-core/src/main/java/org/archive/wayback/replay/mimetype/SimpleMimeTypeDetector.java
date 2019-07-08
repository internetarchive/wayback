package org.archive.wayback.replay.mimetype;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.archive.wayback.core.Resource;
import org.archive.wayback.replay.GzipDecodingResource;
import org.archive.wayback.replay.charset.CharsetDetector;
import org.archive.wayback.replay.charset.StandardCharsetDetector;

/**
 * Simple {@link MimeTypeDetector} implementation.
 * It's ad-hoc and not customizable, just tested against many samples.
 */
public class SimpleMimeTypeDetector implements MimeTypeDetector {
	private static final Logger logger = Logger.getLogger(SimpleMimeTypeDetector.class.getName());

	/**
	 * default value for {@code sniffLength}.
	 */
	public static final int DEFAULT_SNIFF_LENGTH = 1536;
	/**
	 * minimum size of sniffing byte buffer to allocate (to
	 * prevent {@code ArrayIndexOutOfBoundsException}.)
	 */
	protected static final int MINIMUM_SNIFF_BUFFER_SIZE = 10;

	private int sniffLength = DEFAULT_SNIFF_LENGTH;
	private CharsetDetector charsetDetector = new StandardCharsetDetector();

	/**
	 * number of bytes to read from resource.
	 * @param sniffLength
	 */
	public void setSniffLength(int sniffLength) {
		this.sniffLength = sniffLength;
	}

	public int getSniffLength() {
		return sniffLength;
	}

	/**
	 * {@link CharsetDetector} to use for detecting character encoding.
	 * <p>{@link StandardCharsetDetector} is used by default.</p>
	 * @param charsetDetector
	 */
	public void setCharsetDetector(CharsetDetector charsetDetector) {
		if (charsetDetector != null)
			this.charsetDetector = charsetDetector;
		else
			this.charsetDetector = new StandardCharsetDetector();
	}

	private static final String BINARY_FILE = "application/octet-stream";

	/**
	 * Return {@code true} if {@code bytes} looks like a beginning of a
	 * binary file.
	 * <p>Looks for well-known binary format MAGICs.</p>
	 * @param bytes array of bytes.
	 * @return detected mimetype, or {@code null} if no binary
	 * format is detected.
	 */
	private String detectBinaryTypes(byte[] bytes) {
		// NB: there are a lot of specific mimetypes we can detect by file
		// magic, but we don't want to make too much effort here.
		// "application/octet-stream" is fine if file looks very much like
		// binary but don't know what exactly. Returned value is not really
		// used beyond being non-null. We're only concerned with file formats
		// frequently sent out with imprecise Content-Type.
		switch (bytes[0]) {
		case (byte)0xFF:
			if (bytes[1] == (byte)0xFE) {
				// UTF-16LE BOM
				return null;
			} else if ((bytes[1] & 0xFE) == (byte)0xFA) {
				// audio/mp3
				return "audio/mp3";
			} else if (bytes[1] == (byte)0xD8) {
				// image/jpeg - commonly <FF><D8><FF><E0> or <FF><D8><FF><E1>
				return "image/jpeg";
			}
			// WordPerfect (<FF>WPC) falls in this category.
			// (WordPerfect also has <D8>WPC
			return BINARY_FILE;
		case (byte)0xFE:
			if (bytes[1] == (byte)0xFF) {
				// UTF-16BE BOM
				return null;
			}
			break;
		case (byte)0xF7:
			if (bytes[1] == 0x02 && bytes[2] == 0x01) {
				// unconfirmed.
				return "application/x-dvi";
			}
			break;
		case (byte)0xEF:
			if (bytes[1] == (byte)0xBB && bytes[2] == (byte)0xBF) {
				// UTF-8 BOM
				return null;
			}
			break;
		case (byte)0xD0:
			if (bytes[1] == (byte)0xCF && bytes[2] == 0x11 && bytes[2] == (byte)0xE1) {
				// MS Word.Document.8
				return BINARY_FILE;
			}
			break;
		case (byte)0xCA:
			if (bytes[1] == (byte)0xFE && bytes[2] == (byte)0xBA && bytes[3] == (byte)0xBE) {
				// application/java (class files)
				return "application/java";
			}
			break;
		case (byte)0x89:
			if (bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
				return "image/png";
			}
			break;
		case 0x00:
			if (bytes[1] == 0x00) {
				// Windows icon resource falls in this category.
				return BINARY_FILE;
			} else if (bytes[1] == 0x01) {
				// TTF?
				return BINARY_FILE;
			} else if (bytes[1] == 'a' && bytes[2] == 's' && bytes[3] == 'm') {
				return "application/wasm";
			}
			break;
		case 0x01:
			if (bytes[1] == (byte)0xB3 || bytes[1] == (byte)0xBA) {
				return "video/mpeg";
			} else if (bytes[1] == 0x00) {
				// very likely is a binary file
				return BINARY_FILE;
			}
			break;
		case 0x1F:
			if (bytes[1] == (byte)0x8B) {
				return "application/x-gzip";
			} else if (bytes[1] == (byte)0x9D) {
				// followed by 0x90
				return "application/x-compress";
			}
			break;
		case '%':
			if (bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') {
				return "application/pdf";
			} else if (bytes[1] == '!' && bytes[2] == 'P' && bytes[3] == 'S' && bytes[4] == '-') {
				// application/postscript, Type 1 font.
				return "application/postscript";
			}
			break;
		case 'B':
			if (bytes[1] == 'Z' && bytes[2] == 'h') {
				return "application/x-bzip2";
			}
			break;
		case 'F':
			if (bytes[1] == 'W' && bytes[2] == 'S') {
				// followed by <04> or <05>. Also 'CWS<07>?
				return "application/x-shockwave-flash";
			} else if (bytes[1] == 'L' && bytes[2] == 'V' && bytes[3] == 0x01) {
				return "video/x-flv";
			}
			break;
		case 'G':
			if (bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
				// GIF87a or GIF89a
				return "image/gif";
			}
			break;
		case 'M':
			if (bytes[1] == 'Z') {
				// MS-DOS Executable (MZ <00-FF><00-01>)
				if (bytes[3] == 0x00 || bytes[3] == 0x01) {
					return "application/x-dosexec";
				}
			} else if (bytes[1] == 'S' && bytes[2] == 'C' && bytes[3] == 'F') {
				// MS cab file
				return "application/vnd.ms-cab-compressed";
			} else if (bytes[1] == 'T' && bytes[2] == 'h' && bytes[3] == 'd') {
				// MIDI
				return "audio/midi"; // or "application/x-midi"
			}
			break;
		case 'P':
			if (bytes[1] == 'K' && bytes[2] == 0x03 && bytes[3] == 0x04) {
				return "application/zip";
			} else if (bytes[1] == 'E' && bytes[2] == 0x00 && bytes[3] == 0x00 && bytes[4] == 'M' && bytes[5] == 'S') {
				// Windows PE
				return BINARY_FILE;
			}
			break;
		case 'm':
			if (bytes[1] == 'o' && bytes[2] == 'o' && bytes[3] == 'v') {
				return "video/quicktime";
			} else if (bytes[1] == 'd' && bytes[2] == 'a' && bytes[3] == 't') {
				return "video/quicktime";
			}
			break;
		case 'w':
			if (bytes[1] == 'O' && bytes[2] == 'F' && (bytes[3] == 'F' || bytes[3] == '2')) {
				return "application/font-woff";
			}
			break;
		case '{':
			if (bytes[1] == '\\' && bytes[2] == 'r' && bytes[3] == 't' && bytes[4] == 'f' && bytes[5] == '1') {
				return "application/rtf";
			}
			break;
		}

		if (bytes[2] == '-' && bytes[3] == 'l' && bytes[4] == 'h' && bytes[5] == '5' && bytes[6] == '-') {
			// LZH archive
			return BINARY_FILE;
		}
		// Other formats we may want to add
		// "RIFF" <?><?><?><?> "WAVEfmt " - "audio/wav"
		// <DB><A5><2D><00> - commonly with suffix .doc. files command doesn't know this format.
		// <C5><D0><D3><C6><1E><00><00><00> - some EPS has a binary header starting with this, before "%!PS-"
		return null;
	}

	/**
	 * Read first {@code sniffLength} bytes of {@code resource}'s payload,
	 * decoding {@code Content-Encoding} if any. Reset {@code resource}'s
	 * read position back to zero.
	 * @param resource Resource to load bytes from
	 * @return bytes, zero-padded if payload is shorter.
	 * @throws IOException
	 */
	protected byte[] peekContent(Resource resource) throws IOException {
		byte[] bbuffer = new byte[Math.max(sniffLength, MINIMUM_SNIFF_BUFFER_SIZE)];
		String encoding = resource.getHeader("content-encoding");
		if ("gzip".equalsIgnoreCase(encoding) || "x-gzip".equalsIgnoreCase(encoding)) {
			// use larger readlimit, because gzip-ed data can be larger than the original
			// at low compression level.
			resource.mark(sniffLength + 100);
			@SuppressWarnings("resource")
			Resource z = new GzipDecodingResource(resource);
			z.read(bbuffer, 0, sniffLength);
			resource.reset();
		} else {
			resource.mark(sniffLength);
			resource.read(bbuffer, 0, sniffLength);
			resource.reset();
		}
		return bbuffer;
	}

	@Override
	public String sniff(Resource resource) {
		// This sniffer only works with HTTP response record.
		// TODO: check record type.

		byte[] bbuffer;
		try {
			bbuffer = peekContent(resource);
		} catch (IOException ex) {
			// Caveat: IOException from reset() (i.e. mark got invalidated) will have major
			// consequences. Should we re-throw some runtime exception?
			logger.warning("error reading " + sniffLength + " from resource: " + ex.getMessage());
			return null;
		}

		// Spare decoding and regexp-matching for clearly-binary files.
		// Most mimetype detector libraries are overkill since we don't
		// need to know the details (ex. bitrate of MP3, PDF version).
		// So we use our own detector.
		String ctype = detectBinaryTypes(bbuffer);
		if (ctype != null) {
			// Very like be a binary file.
			return ctype;
		}

		// Try decoding as text and look for signature patterns.
		// It doesn't need to be too complex, since want to differentiate
		// only a handful of text types: HTML, JAVASCRIPT, JSON and CSS.
		String encoding;
		try {
			encoding = charsetDetector.getCharset(resource, null);
		} catch (IOException ex1) {
			// IO error at this stage means we won't be able to sniff
			// content type either.
			// TODO: log
			return null;
		}
		//System.err.println("detected encoding: " + encoding);
		String text;
		try {
			text = new String(bbuffer, encoding);
		} catch (UnsupportedEncodingException ex) {
			// likely to happen, already checked by CharsetDetector.
			return null;
		}
		// strip off BOM - all variants are decoded into \ufeff.
		if (text.length() > 0 && text.charAt(0) == '\ufeff') {
			text = text.substring(1);
		}

		ctype = detectJavaScript(text);
		if (ctype != null) return ctype;

		ctype = detectHTML(text);
		if (ctype != null) return ctype;

		ctype = detectCSS(text);
		if (ctype != null) return ctype;

		return null;
	}

	private static final Pattern RE_XML_PROLOGUE = Pattern
			.compile("\\s*<\\?xml\\s+version=\"[.\\d]+\"\\s+.*\\?>");
	private static final Pattern RE_HTML_ELEMENTS = Pattern
			.compile("(?i)\\s*<(HTML|HEAD|STYLE|SCRIPT|META|BODY)(\\s|>)");
	private static final Pattern RE_DOCTYPE_HTML = Pattern
			.compile("(?i)\\s*<!DOCTYPE\\s+HTML");
	private static final Pattern RE_SGML_COMMENT = Pattern
			.compile("(?s)\\s*<!--.*?-->");
	private static final Pattern RE_END_TAG = Pattern
			.compile("(?i)</[a-z][a-z0-9]*>");

	protected String detectHTML(String text) {
		int pos = 0;
		{
			Matcher m = RE_XML_PROLOGUE.matcher(text);
			if (m.lookingAt()) {
				pos = m.end();
			}
		}
		{
			Matcher m = RE_SGML_COMMENT.matcher(text);
			m.region(pos, text.length());
			while (m.lookingAt()) {
				m.region(pos = m.end(), text.length());
			}
		}
		{
			Matcher m = RE_DOCTYPE_HTML.matcher(text);
			m.region(pos, text.length());
			if (m.lookingAt())
				return "text/html";
		}
		{
			Matcher m = RE_HTML_ELEMENTS.matcher(text);
			m.region(pos, text.length());
			if (m.lookingAt())
				return "text/html";
		}
		{
			Matcher m = RE_END_TAG.matcher(text);
			m.region(pos, text.length());
			if (m.find())
				return "text/html";
		}
		return null;
	}

	private static final Pattern RE_JS_WS_OR_COMMENT = Pattern
		.compile("(?m)\\s+|/\\*.*?\\*/|//.*$");
	private static final Pattern RE_JS_VAR = Pattern
		.compile("(?m)^var\\s+[_a-zA-Z$][_a-zA-Z$0-9]+");
	private static final Pattern RE_JS_FUNCTION = Pattern
		.compile("(?s)\\(?function(?:\\s+[a-zA-Z0-9_$]+\\s*)?\\(");
	private static final Pattern RE_JS_DOCUMENT_WRITE = Pattern.compile("document\\.write\\(");
	private static final Pattern RE_JSON_HEAD = Pattern
		.compile("\\s*\\{\\s*\"");

	protected String detectJavaScript(String text) {
		int pos = 0;
		{
			Matcher m = RE_JS_WS_OR_COMMENT.matcher(text);
			while (m.lookingAt()) {
				m.region(pos = m.end(), text.length());
				
			}
		}
		{
			Matcher m = RE_JS_VAR.matcher(text);
			m.region(pos, text.length());
			if (m.lookingAt())
				return "text/javascript";
		}
		{
			Matcher m = RE_JS_FUNCTION.matcher(text);
			m.region(pos, text.length());
			if (m.lookingAt())
				return "text/javascript";
		}
		{
			Matcher m = RE_JS_DOCUMENT_WRITE.matcher(text);
			m.region(pos, text.length());
			if (m.lookingAt())
				return "text/javascript";
		}
		{
			Matcher m = RE_JSON_HEAD.matcher(text);
			m.region(pos, text.length());
			if (m.lookingAt()) {
				// TODO: if resource has content-type "text/javascript", just
				// use it.
				return "application/json";
			}
		}
		return null;
	}

	private static final Pattern RE_CSS_COMMENT = Pattern.compile("\\s*/\\*.*?\\*/");
	private static final Pattern RE_CSS_AT_RULE = Pattern.compile("\\s*@(import|media|document|charset|font-face|keyframes|namespace|supports)\\s+");
	private static final String RE_CSS_SIMPLE_SELECTOR = "(?:(?:[-a-z0-9]+|\\*)(?:[.#:][-_a-z0-9]+|\\[.+?\\])*|(?:[.#:][-_a-z0-9]+|\\[.+?\\])+)";
	private static final Pattern RE_CSS_RULESET_START = Pattern
		.compile("(?i)\\s*" + RE_CSS_SIMPLE_SELECTOR + "(?:[\\s,+>]+" + RE_CSS_SIMPLE_SELECTOR + ")*\\s*\\{");
	private static final Pattern RE_CSS_DECLARATION = Pattern
		.compile("(?i)\\s*[-a-z]+\\s*:\\s*[^;}]+[;}]");

	protected String detectCSS(String text) {
		// CSS (they are rarely returned with mimetype "unk" or "text/html")
		int pos = 0;
		Matcher cm = RE_CSS_COMMENT.matcher(text);
		{
			cm.region(pos, text.length());
			while (cm.lookingAt()) {
				cm.region(pos = cm.end(), text.length());
			}
		}
		{
			Matcher m = RE_CSS_AT_RULE.matcher(text);
			m.region(pos,  text.length());
			if (m.lookingAt()) {
				return "text/css";
			}
		}
		{
			Matcher m = RE_CSS_RULESET_START.matcher(text);
			m.region(pos,  text.length());
			if (m.lookingAt()) {
				cm.region(pos = m.end(), text.length());
				while (cm.lookingAt()) {
					cm.region(pos = cm.end(), text.length());
				}
				Matcher sm = RE_CSS_DECLARATION.matcher(text);
				sm.region(pos, text.length());
				if (sm.lookingAt()) {
					return "text/css";
				}
			}
		}
		return null;
	}
}