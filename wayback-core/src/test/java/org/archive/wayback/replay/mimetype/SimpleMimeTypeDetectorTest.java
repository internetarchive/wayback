package org.archive.wayback.replay.mimetype;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import junit.framework.TestCase;

import org.archive.io.warc.TestWARCReader;
import org.archive.io.warc.TestWARCRecordInfo;
import org.archive.io.warc.WARCRecord;
import org.archive.io.warc.WARCRecordInfo;
import org.archive.wayback.core.Resource;
import org.archive.wayback.resourcestore.jwat.JWATResource;
import org.archive.wayback.resourcestore.resourcefile.WarcResource;

/**
 * Test for {@link SimpleMimeTypeDetector}
 */
public class SimpleMimeTypeDetectorTest extends TestCase {

	SimpleMimeTypeDetector cut;

	/* (non-Javadoc)
	 * @see junit.framework.TestCase#setUp()
	 */
	protected void setUp() throws Exception {
		super.setUp();
		cut = new SimpleMimeTypeDetector();
	}

	public static Resource createTestResource(String ctype, byte[] payloadBytes, boolean compressed)
			throws IOException {
		WARCRecordInfo recinfo = compressed ? TestWARCRecordInfo
				.createCompressedHttpResponse(ctype, payloadBytes)
					: TestWARCRecordInfo.createHttpResponse(ctype, payloadBytes);
		TestWARCReader ar = new TestWARCReader(recinfo);
		WARCRecord rec = ar.get(0);
		WarcResource resource = new WarcResource(rec, ar);
		resource.parseHeaders();
		return resource;
	}

	protected byte[] getTestContent(String filename) throws IOException {
		ByteArrayOutputStream bao = new ByteArrayOutputStream();
		InputStream is = getClass().getResourceAsStream(filename);
		assertNotNull("test resource " + filename + " is missing", is);
		byte[] buf = new byte[8192];
		int n;
		while ((n = is.read(buf)) > 0) {
			bao.write(buf, 0, n);
		}
		return bao.toByteArray();
	}

	protected String detectMimeType(String filename, String indexContentType,
			String recordContentType) throws IOException {
		//WaybackRequest wbRequest = new WaybackRequest();
		//CaptureSearchResult result = new CaptureSearchResult();
		//result.setMimeType(indexContentType);
		Resource resource = createTestResource(recordContentType,
			getTestContent(filename), false);
		String mimetype = cut.sniff(resource);
		return mimetype;
	}

	protected String detectMimeType(String filename, String indexContentType) throws IOException {
		String recordContentType = "unk".equals(indexContentType) ? null
				: indexContentType;
		return detectMimeType(filename, indexContentType, recordContentType);
	}

	// Content-Type sniffing tests

	public void testContentSniffing_HTML() throws Exception {
		// CDX writer writes "unk" in content-type field if Content-Type
		// header is missing.
		assertEquals("text/html", detectMimeType("html/1.html", "unk"));
		assertEquals("text/html", detectMimeType("html/2.html", "unk"));
		assertEquals("text/html", detectMimeType("html/3.html", "unk"));
		assertEquals("text/html", detectMimeType("html/4.html", "text/html"));
		assertEquals("text/html", detectMimeType("html/5.html", "text/html"));
		assertEquals("text/html", detectMimeType("html/6.html", "text/html"));
	}

	/**
	 * Detect pattern explosion caused by overly flexible regular
	 * expression.
	 * @throws Exception
	 */
	public void testContentSniffing_runawayRegexp() throws Exception {
		// 7.html has a sequence of TABs and LFs. One example found in production.
		ExecutorService exec = Executors.newSingleThreadExecutor();
		Future<String> future = exec.submit(new Callable<String>() {
			@Override
			public String call() throws Exception {
				return detectMimeType("html/7.html", "text/html");
			}
		});
		try {
			String result = future.get(10, TimeUnit.SECONDS);
			assertEquals(null, result);
		} catch (TimeoutException ex) {
			fail("sniff did not finish within 10 seconds");
			future.cancel(true);
		}
	}

	public void testContentSniffing_JavaScript() throws Exception {
		assertEquals("text/javascript", detectMimeType("js/1.js", "text/html"));
		assertEquals("text/javascript", detectMimeType("js/2.js", "text/html"));
		assertEquals("text/javascript", detectMimeType("js/3.js", "text/html"));
	}

	public void testContentSniffing_JavaScript_compressed() throws Exception {
		Resource resource = createTestResource("text/html", getTestContent("js/1.js"), true);
		String mimetype = cut.sniff(resource);

		assertEquals("text/javascript", mimetype);
		// resource's payload stream must be positioned at the beginning,
		// which is confirmed by testing if the first two bytes are GZIP MAGIC.
		byte[] bytes = new byte[2];
		resource.read(bytes);
		assertTrue("resource is properly reset to position 0",
			bytes[0] == (byte)0x1f && bytes[1] == (byte)0x8b);
	}

	public void testContentSniffing_JSON() throws Exception {
		assertEquals("application/json", detectMimeType("json/1.json", "application/javascript"));
	}

	public void testContentSniffing_CSS() throws Exception {
		assertEquals("text/css", detectMimeType("css/1.css", "unk"));
		assertEquals("text/css", detectMimeType("css/2.css", "unk"));
		assertEquals("text/css", detectMimeType("css/3.css", "unk"));
		assertEquals("text/css", detectMimeType("css/4.css", "unk"));
	}

	public void testContentSniffing_Binary() throws Exception {
		assertEquals("application/pdf", detectMimeType("bin/1.pdf", "unk"));
		assertEquals("image/png", detectMimeType("bin/2.png", "text/html"));
		assertEquals("application/wasm", detectMimeType("bin/4.wasm", "application/octet-stream; charset=utf-8"));
	}

	/**
	 * Main entry point for testing against real-world samples.
	 * <p>
	 * Needs following JARs to run:
	 * <ul>
	 * <li>wayback-core</li>
	 * <li>junit 3</li>
	 * <li>commons-httpclient 3.1</li>
	 * <li>juniversalchardet</li>
	 * <li>jwat-{arc,warc,gzip,common}</li>
	 * </ul>
	 */
	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("Arguments: INPUT-FILE");
			System.err.println("  INPUT-FILE is a file containing just one gzip-ed W/ARC record,");
			System.err.println("  or a directory containing such files (must have .gz suffix)");
			System.exit(1);
		}
		File input = new File(args[0]);

		SimpleMimeTypeDetector detector = new SimpleMimeTypeDetector();

		if (input.isDirectory()) {
			File[] inputFiles = input.listFiles(new FileFilter() {
				@Override
				public boolean accept(File pathname) {
					return pathname.isFile() && pathname.getName().endsWith(".gz");
				}
			});
			for (File f : inputFiles) {
				detectFile(detector, f);
			}
		} else {
			detectFile(detector, input);
		}
	}

	public static void detectFile(MimeTypeDetector detector, File file) {
		try {
			InputStream is = new FileInputStream(file);
			Resource resource = JWATResource.getResource(is, 0);
			String contentType = resource.getHeader("content-type");
			if (contentType == null)
				contentType = "-";
			else {
				int p = contentType.indexOf(';');
				if (p >= 0) {
					contentType = contentType.substring(0, p).trim();
				}
			}
			String mimeType = detector.sniff(resource);
			if (mimeType == null) mimeType = "-";
			System.out.println(file.getPath() + "\t" + contentType + "\t" + mimeType);
		} catch (Exception ex) {
			System.out.println(file.getPath() + "\t" + "-" + "\tERROR " + ex.getMessage());
		}
	}
}
