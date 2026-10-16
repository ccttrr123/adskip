import java.io.*;
import java.net.*;
import java.nio.file.*;

/**
 * 用 JDK 自带 JSSE 的极简 HTTPS 下载器。等价于 curl，但在下面这种环境里能工作而 curl 不能：
 *
 * Windows 的 schannel 在 TLS 握手时需要往 %APPDATA%\Microsoft\Crypto 写临时密钥容器。
 * 如果该目录不可写（例如进程被文件沙箱限制在工作区内），所有 HTTPS 请求都会失败并报：
 *     schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS
 *     (No credentials are available in the security package)
 * JDK 的 JSSE 不依赖 schannel，因此可以绕过这个限制。
 *
 * 用法： java Download.java <url> <output-path>
 */
public class Download {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java Download.java <url> <output-path>");
            System.exit(64);
        }
        String url = args[0];
        Path out = Paths.get(args[1]).toAbsolutePath();
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(30_000);
        c.setReadTimeout(120_000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

        int code = c.getResponseCode();
        long total = c.getContentLengthLong();
        System.out.println("HTTP " + code + "  contentLength=" + total + "  url=" + c.getURL());
        if (code >= 400) {
            System.out.println("FAILED with status " + code);
            System.exit(2);
        }

        long done = 0;
        long lastLog = 0;
        byte[] buf = new byte[1 << 16];
        try (InputStream in = c.getInputStream();
             OutputStream fos = new BufferedOutputStream(
                     Files.newOutputStream(out, StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING))) {
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastLog > 2000) {
                    lastLog = now;
                    if (total > 0) {
                        System.out.printf("  %.1f / %.1f MB (%.1f%%)%n",
                                done / 1048576.0, total / 1048576.0, done * 100.0 / total);
                    } else {
                        System.out.printf("  %.1f MB%n", done / 1048576.0);
                    }
                    System.out.flush();
                }
            }
        }
        System.out.println("DONE " + done + " bytes -> " + out);
    }
}
