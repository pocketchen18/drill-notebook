package com.drillnotebook.app.service;

import com.drillnotebook.app.config.PortablePathResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Service;

@Service
public class AttachmentStorageService {
    private final PortablePathResolver paths;

    public AttachmentStorageService(PortablePathResolver paths) { this.paths = paths; }

    public StoreResult store(long pageId, String fileName, InputStream input) throws IOException {
        Path attachmentsRoot = paths.data().resolve("attachments").resolve(String.valueOf(pageId));
        Files.createDirectories(attachmentsRoot);
        String extension = extractExtension(fileName);
        String storedName = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);
        Path target = attachmentsRoot.resolve(storedName);

        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }

        long totalBytes;
        try (var digestStream = new DigestInputStream(input, digest)) {
            totalBytes = Files.copy(digestStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        String sha256 = bytesToHex(digest.digest());
        String mimeType = guessMimeType(fileName);
        String storagePath = "attachments/" + pageId + "/" + storedName;
        return new StoreResult(storagePath, sha256, mimeType, totalBytes);
    }

    public InputStream openInputStream(String storagePath) throws IOException {
        return Files.newInputStream(resolveAbsolutePath(storagePath));
    }

    public long fileSize(String storagePath) throws IOException {
        return Files.size(resolveAbsolutePath(storagePath));
    }

    public void deleteFile(String storagePath) throws IOException {
        Files.deleteIfExists(resolveAbsolutePath(storagePath));
    }

    /**
     * 列出 zip 压缩包内部文件条目（名称 + 解压后大小），仅用于预览展示，
     * 不读取文件内容。最多返回 500 条，目录条目被跳过。
     * 中文 Windows 打包的 zip 常用 GBK 编码文件名，UTF-8 解码会抛
     * ZipException，故先试 UTF-8 失败后回退 GBK。
     */
    public List<Map<String, Object>> listZipEntries(String storagePath) throws IOException {
        Path abs = resolveAbsolutePath(storagePath);
        IOException last = null;
        for (Charset charset : new Charset[] { StandardCharsets.UTF_8, Charset.forName("GBK") }) {
            try {
                return readZipEntries(abs, charset);
            } catch (IOException error) {
                last = error;
            }
        }
        throw last != null ? last : new IOException("无法读取压缩包");
    }

    private List<Map<String, Object>> readZipEntries(Path abs, Charset charset) throws IOException {
        var result = new ArrayList<Map<String, Object>>();
        try (ZipFile zip = new ZipFile(abs.toFile(), charset)) {
            var entries = zip.entries();
            while (entries.hasMoreElements() && result.size() < 2000) {
                var entry = entries.nextElement();
                var item = new LinkedHashMap<String, Object>();
                item.put("name", entry.getName());
                item.put("size", Math.max(entry.getSize(), 0L));
                item.put("dir", entry.isDirectory());
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 解析 pptx 每一页幻灯片的文本段落（按页序返回），仅用于预览展示。
     * pptx 本质是 zip，直接读取 ppt/slides/slideN.xml 抽取 <a:p>/<a:t> 文本，
     * 不引入 POI。解析失败时抛出 IOException。
     */
    public List<Map<String, Object>> listPptxSlides(String storagePath) throws IOException {
        Path abs = resolveAbsolutePath(storagePath);
        try (ZipFile zip = new ZipFile(abs.toFile(), StandardCharsets.UTF_8)) {
            List<String> slideNames = new ArrayList<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) {
                    slideNames.add(name);
                }
            }
            slideNames.sort(Comparator.comparingInt(AttachmentStorageService::slideNumber));
            var result = new ArrayList<Map<String, Object>>();
            for (String slideName : slideNames) {
                var entry = zip.getEntry(slideName);
                if (entry == null) continue;
                Map<String, String> rels = readSlideRels(zip, slideName);
                SlideParse parsed;
                try (InputStream input = zip.getInputStream(entry)) {
                    parsed = parseSlide(input);
                }
                var images = new ArrayList<String>();
                for (String embedId : parsed.embedIds) {
                    String target = rels.get(embedId);
                    if (target != null && target.startsWith("ppt/media/")) images.add(target);
                }
                var item = new LinkedHashMap<String, Object>();
                item.put("paragraphs", parsed.paragraphs);
                item.put("images", images);
                result.add(item);
            }
            return result;
        }
    }

    /** 读取 pptx 内部媒体文件（ppt/media/...）字节，用于幻灯片图片预览。 */
    public byte[] readMediaBytes(String storagePath, String mediaPath) throws IOException {
        Path abs = resolveAbsolutePath(storagePath);
        try (ZipFile zip = new ZipFile(abs.toFile(), StandardCharsets.UTF_8)) {
            var entry = zip.getEntry(mediaPath);
            if (entry == null || entry.isDirectory()) throw new IOException("媒体不存在");
            try (InputStream input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static final class SlideParse {
        final List<String> paragraphs = new ArrayList<>();
        final List<String> embedIds = new ArrayList<>();
    }

    private static int slideNumber(String name) {
        String base = name.substring(name.lastIndexOf('/') + 1);
        String digits = base.replaceAll("\\D", "");
        try { return Integer.parseInt(digits); } catch (NumberFormatException error) { return Integer.MAX_VALUE; }
    }

    private static XMLInputFactory newXmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        return factory;
    }

    private static Map<String, String> readSlideRels(ZipFile zip, String slideName) throws IOException {
        String relsName = slideName.replaceFirst("ppt/slides/", "ppt/slides/_rels/") + ".rels";
        var map = new LinkedHashMap<String, String>();
        var entry = zip.getEntry(relsName);
        if (entry == null) return map;
        try (InputStream input = zip.getInputStream(entry)) {
            XMLStreamReader reader = newXmlFactory().createXMLStreamReader(input);
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(reader.getLocalName())) {
                    String id = reader.getAttributeValue(null, "Id");
                    String target = reader.getAttributeValue(null, "Target");
                    if (id != null && target != null) {
                        map.put(id, target.startsWith("../") ? "ppt/" + target.substring(3) : target);
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException error) {
            throw new IOException(error);
        }
        return map;
    }

    private SlideParse parseSlide(InputStream input) throws IOException {
        var parse = new SlideParse();
        try {
            XMLStreamReader reader = newXmlFactory().createXMLStreamReader(input);
            StringBuilder current = null;
            boolean inText = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("p".equals(local)) current = new StringBuilder();
                    else if ("t".equals(local)) inText = true;
                    else if ("br".equals(local) && current != null) current.append('\n');
                    else if ("tab".equals(local) && current != null) current.append('\t');
                    else if ("blip".equals(local)) {
                        String embed = reader.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed");
                        if (embed != null) parse.embedIds.add(embed);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("t".equals(local)) inText = false;
                    else if ("p".equals(local)) {
                        if (current != null) {
                            String text = current.toString().trim();
                            if (!text.isEmpty()) parse.paragraphs.add(text);
                        }
                        current = null;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS) {
                    if (inText && current != null) current.append(reader.getText());
                }
            }
            reader.close();
        } catch (XMLStreamException error) {
            throw new IOException(error);
        }
        return parse;
    }

    public Path resolveAbsolutePath(String storagePath) {
        return paths.data().resolve(storagePath).normalize();
    }

    private static String extractExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String guessMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".rar")) return "application/x-rar-compressed";
        if (lower.endsWith(".7z")) return "application/x-7z-compressed";
        return "application/octet-stream";
    }

    private static String bytesToHex(byte[] bytes) {
        var builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) builder.append(String.format("%02x", b));
        return builder.toString();
    }

    public record StoreResult(String storagePath, String sha256, String mimeType, long fileSize) {}
}
