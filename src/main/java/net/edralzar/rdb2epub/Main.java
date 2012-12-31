package net.edralzar.rdb2epub;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import net.edralzar.rdb2epub.oauth.ReadabilityApi;
import net.edralzar.rdb2epub.oauth.ReadabilityConst;
import nl.siegmann.epublib.domain.Author;
import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.epub.EpubWriter;
import nl.siegmann.epublib.service.MediatypeService;

import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.PrettyHtmlSerializer;
import org.htmlcleaner.TagNode;
import org.scribe.builder.ServiceBuilder;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuthService;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class Main {

	public static final Pattern IMG = Pattern
			.compile("<img.+src=\"([^\"]+)\".+>");

	public static final String COVERLOGO_FILENAME = "cover_logo.png";
	public static final int COVERLOGO_HEIGHT = 250;
	public static final int COVERLOGO_WIDTH = 320;
	public static final int COVER_WIDTH = 760;
	public static final int COVER_HEIGHT = 900;

	public static final String TOKEN_FILENAME = "token";

	public static void main(String[] args) {
		OAuthService service = new ServiceBuilder()
				.apiKey(ReadabilityConst.API_KEY)
				.apiSecret(ReadabilityConst.API_SECRET)
				.provider(ReadabilityApi.class).callback("oob").build();
		Token accessToken = null;
		File tokenFile = new File(TOKEN_FILENAME);
		if (tokenFile.exists() && tokenFile.isFile()) {
			try {
				accessToken = (Token) new ObjectInputStream(
						new FileInputStream(tokenFile)).readObject();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else {
			Token reqToken = service.getRequestToken();

			String authUrl = service.getAuthorizationUrl(reqToken);

			System.out.println("Please go to " + authUrl
					+ " to get an authentication PIN and enter it there");

			BufferedReader in = new BufferedReader(new InputStreamReader(
					System.in));
			try {
				String pin = in.readLine();

				System.out.println("Verifying with PIN " + pin);

				Verifier v = new Verifier(pin);
				accessToken = service.getAccessToken(reqToken, v);

				new ObjectOutputStream(new FileOutputStream(TOKEN_FILENAME))
						.writeObject(accessToken);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		if (accessToken != null && !accessToken.isEmpty()) {
			getLastBookmarks(service, accessToken);
			System.out.println("DONE");
		} else {
			System.err.println("Unable to get access to the API");
		}
	}

	private static void getLastBookmarks(OAuthService service, Token accessToken) {
		String since = null;
		File f = new File("lastSync");
		if (f.exists()) {
			// get the date of last synchronization
			Path p = FileSystems.getDefault().getPath(f.getAbsolutePath());
			try {
				FileTime lastModified = Files.getLastModifiedTime(p);
				SimpleDateFormat sdf = new SimpleDateFormat(
						"yyyyMMdd'T'hh:mm:ss");
				Date d = new Date(lastModified.toMillis());
				Calendar c = Calendar.getInstance();
				c.setTime(d);
				c.setTimeZone(TimeZone.getTimeZone("UTC"));
				since = sdf.format(c.getTime());
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		OAuthRequest request = new OAuthRequest(Verb.GET,
				"https://www.readability.com/api/rest/v1/bookmarks/");
		request.addQuerystringParameter("archive", "0");
		if (since != null) {
			request.addQuerystringParameter("addedSince", since);
			System.out.println("will get bookmarks added since " + since);
		}

		service.signRequest(accessToken, request);
		Response response = request.send();

		JsonParser parser = new JsonParser();
		JsonObject json = parser.parse(response.getBody()).getAsJsonObject();
		JsonArray bookmarks = json.getAsJsonArray("bookmarks");
		for (JsonElement b : bookmarks) {
			JsonObject article = b.getAsJsonObject().getAsJsonObject("article");
			String articleId = article.get("id").getAsString();
			String title = article.get("title").getAsString();

			File epubFile = getEpubName(articleId, title);
			if (!epubFile.exists()) {
				epubArticle(service, accessToken, articleId, epubFile);
			} else {
				System.out.println("Article \"" + title
						+ "\" was already present as \"" + epubFile.getName()
						+ "\"\n SKIPPED");
			}
		}

		if (f.exists()) {
			f.delete();
		}
		try {
			f.createNewFile();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private static File getEpubName(String articleId, String title) {
		StringBuilder fileName = new StringBuilder(title);
		if (fileName.length() > 15) {
			fileName.delete(15, fileName.length());
		}
		fileName.insert(0, '_');
		fileName.insert(0, articleId);
		fileName.insert(0, "rdb_");
		fileName.append(".epub");
		String fileNameClean = fileName.toString().replaceAll(
				"[^\\w)\\-.(\\s]", " ");
		return new File(fileNameClean);
	}

	private static void epubArticle(OAuthService service, Token accessToken,
			String articleId, File epubFile) {
		OAuthRequest request = new OAuthRequest(Verb.GET,
				"https://www.readability.com/api/rest/v1/articles/" + articleId);

		service.signRequest(accessToken, request);
		Response response = request.send();

		Gson gson = new Gson();
		JsonParser parser = new JsonParser();
		JsonObject json = parser.parse(response.getBody()).getAsJsonObject();
		String content = gson.fromJson(json.get("content"), String.class);
		String title = gson.fromJson(json.get("title"), String.class);
		String author = gson.fromJson(json.get("author"), String.class);

		System.out.println("Preparing epub for article " + articleId + ", "
				+ title);

		// prepare a new epub
		Book book = new Book();

		// isolate images
		Matcher imageDetection = IMG.matcher(content);
		List<ImageReplacement> imageReplacements = new ArrayList<ImageReplacement>();
		while (imageDetection.find()) {
			String src = imageDetection.group(1);
			String imageName = "img/" + src.substring(src.lastIndexOf('/') + 1);

			imageReplacements.add(new ImageReplacement(src, imageName, src,
					imageName));
		}

		// set the title and author
		book.getMetadata().addAuthor(new Author("Readability"));
		if (author != null) {
			book.getMetadata().addAuthor(new Author(author));
		}
		book.getMetadata().addTitle(title);

		// images to local
		for (ImageReplacement replacement : imageReplacements) {
			// rework the body to get xhtml with local images
			content = content.replace(replacement.originalImg,
					replacement.epubImg);

			// get the image as a an epub resource
			InputStream urlStream = null;
			try {
				urlStream = new URL(replacement.imageSource).openStream();
				book.getResources().add(
						new Resource(urlStream, replacement.epubImgHref));
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				if (urlStream != null) {
					try {
						urlStream.close();
					} catch (IOException e) {
					}
				}
			}
		}

		// prepare cover
		Resource coverImage = generateImageCover(title);
		if (coverImage != null) {
			book.setCoverImage(coverImage);
			StringBuilder cover = new StringBuilder(
					"<html xmlns=\"http://www.w3.org/1999/xhtml\">");
			cover.append("\n<head>\n<title>").append(title)
					.append("</title>\n");
			cover.append(" <style type=\"text/css\"> img { max-width: 100%; }</style>\n");
			cover.append("</head>");
			cover.append("\n<body>\n");
			cover.append("<img src=\"cover.png\" alt=\"").append(title)
					.append("\" />");
			cover.append("\n</body>\n</html>");

			Resource coverResource = new Resource(cover.toString().getBytes(),
					MediatypeService.XHTML);
			coverResource.setHref("cover.html");
			book.setCoverPage(coverResource);
		}

		// add chapter
		StringBuilder xhtml = new StringBuilder(
				"<html xmlns=\"http://www.w3.org/1999/xhtml\">");
		xhtml.append("\n<head>\n<title>").append(title)
				.append("</title>\n</head>");
		xhtml.append("\n<body>\n");
		xhtml.append(content);
		xhtml.append("\n</body>\n</html>");
		try {
			HtmlCleaner cleaner = new HtmlCleaner();
			TagNode clean = cleaner.clean(xhtml.toString());
			String epubContent = new PrettyHtmlSerializer(
					cleaner.getProperties()).getAsString(clean);
			epubContent = epubContent.replace("<html>",
					"<html xmlns=\"http://www.w3.org/1999/xhtml\">");
			// System.err.println(epubContent);

			book.addSection(articleId, new Resource(new StringReader(
					epubContent), articleId + ".html"));
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		// write the epub
		EpubWriter writer = new EpubWriter();
		try {
			writer.write(book, new FileOutputStream(epubFile));
			System.out.println(epubFile + " created successfully");
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private static Resource generateImageCover(String title) {
		// create the logo canvas
		BufferedImage cover = new BufferedImage(COVER_WIDTH, COVER_HEIGHT,
				BufferedImage.TYPE_BYTE_GRAY);
		Graphics g = cover.getGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, COVER_WIDTH, COVER_HEIGHT);
		g.setColor(Color.BLACK);

		// insert the cover logo
		try {
			BufferedImage logo = ImageIO.read(Main.class
					.getResource("cover_logo.png"));
			g.drawImage(logo, (COVER_WIDTH - COVERLOGO_WIDTH) / 2, 100, null);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		int yOffset = 100 + COVERLOGO_HEIGHT + 100;

		// draw the title, wrapping when not enough space on the line
		g.setFont(new Font("georgia", Font.BOLD | Font.ITALIC, 65));
		FontMetrics metrics = g.getFontMetrics();
		int availableWidth = cover.getWidth() - 60;
		String[] words = title.split(" ");
		StringBuilder buff = new StringBuilder();

		for (String word : words) {
			String prev = buff.toString();
			buff.append(word).append(' ');

			if (metrics.stringWidth(buff.toString()) > availableWidth) {
				// Draw text
				g.drawString(prev, 10, yOffset);
				yOffset += metrics.getLineMetrics(prev, g).getHeight() + 10;
				// Clean the drawn part of the buffer
				buff.delete(0, prev.length());
			}
			if (word == words[words.length - 1]) {
				// final drawing of text
				g.drawString(buff.toString(), 10, yOffset);
			}
		}

		// create the resource from buffered image
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(cover, "png", baos);
			baos.flush();
			byte[] imageInByte = baos.toByteArray();
			baos.close();
			Resource coverResource = new Resource(imageInByte,
					MediatypeService.PNG);
			coverResource.setHref("cover.png");

			return coverResource;
		} catch (Exception e) {
			// TODO
			e.printStackTrace();
		}
		return null;
	}

	private static class ImageReplacement {
		public final String originalImg;
		public final String epubImg;
		public final String imageSource;
		public final String epubImgHref;

		public ImageReplacement(String originalImg, String epubImg,
				String imageSource, String epubImgHref) {
			this.originalImg = originalImg;
			this.epubImg = epubImg;
			this.imageSource = imageSource;
			this.epubImgHref = epubImgHref;
		}
	}
}

