package info.wesite.web.controller;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.concurrent.TimeUnit;

/**
 * Dynamic OG Image Generator
 * GET /og-image.png?title=...&subtitle=...&type=tool|blog|domain
 *
 * Produces a 1200×630 PNG suitable for og:image / twitter:card.
 */
@Controller
public class OgImageController {

    private static final int W = 1200;
    private static final int H = 630;
    private static final Color BG_DARK   = new Color(8, 12, 24);
    private static final Color BG_CARD   = new Color(15, 22, 40);
    private static final Color CYAN      = new Color(0, 229, 255);
    private static final Color CYAN_DIM  = new Color(0, 180, 200, 80);
    private static final Color TEXT_HI   = new Color(226, 232, 240);
    private static final Color TEXT_LO   = new Color(122, 139, 160);
    private static final Color BORDER    = new Color(255, 255, 255, 20);

    @GetMapping(value = "/og-image.png", produces = "image/png")
    public ResponseEntity<byte[]> generate(
            @RequestParam(defaultValue = "Whose.Domains") String title,
            @RequestParam(defaultValue = "Free Domain WHOIS Lookup & DNS Tools") String subtitle,
            @RequestParam(defaultValue = "default") String type) throws IOException {

        // Sanitise
        title    = StringUtils.abbreviate(title,    72);
        subtitle = StringUtils.abbreviate(subtitle, 100);

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,        RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,   RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,           RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,   RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        // ── Background ──────────────────────────────────────────────
        g.setColor(BG_DARK);
        g.fillRect(0, 0, W, H);

        // Subtle radial glow top-left
        RadialGradientPaint glow = new RadialGradientPaint(
                new java.awt.geom.Point2D.Float(200, 150), 500,
                new float[]{0f, 1f},
                new Color[]{new Color(0, 229, 255, 30), new Color(0, 0, 0, 0)});
        g.setPaint(glow);
        g.fillRect(0, 0, W, H);

        // Grid lines
        g.setColor(new Color(0, 229, 255, 8));
        g.setStroke(new BasicStroke(1f));
        for (int x = 0; x < W; x += 60) g.drawLine(x, 0, x, H);
        for (int y = 0; y < H; y += 60) g.drawLine(0, y, W, y);

        // ── Card background ─────────────────────────────────────────
        int pad = 60;
        RoundRectangle2D card = new RoundRectangle2D.Float(pad, pad, W - 2*pad, H - 2*pad, 24, 24);
        g.setColor(BG_CARD);
        g.fill(card);
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(card);

        // Top accent line
        g.setColor(CYAN);
        g.setStroke(new BasicStroke(3f));
        g.drawLine(pad + 24, pad, W - pad - 24, pad);

        // ── Logo / Brand ────────────────────────────────────────────
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.setColor(CYAN);
        g.drawString("whose.domains", pad + 48, pad + 52);

        // Type badge
        String badge = getBadge(type);
        if (badge != null) {
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            FontMetrics bfm = g.getFontMetrics();
            int bw = bfm.stringWidth(badge) + 24;
            g.setColor(CYAN_DIM);
            g.fillRoundRect(W - pad - 48 - bw, pad + 30, bw, 26, 13, 13);
            g.setColor(CYAN);
            g.drawString(badge, W - pad - 48 - bw + 12, pad + 48);
        }

        // ── Title ───────────────────────────────────────────────────
        int titleY = 260;
        Font titleFont = new Font("SansSerif", Font.BOLD, chooseFontSize(title));
        g.setFont(titleFont);
        g.setColor(TEXT_HI);
        drawWrappedText(g, title, pad + 48, titleY, W - 2*pad - 96, titleFont);

        // ── Subtitle ────────────────────────────────────────────────
        g.setFont(new Font("SansSerif", Font.PLAIN, 26));
        g.setColor(TEXT_LO);
        drawWrappedText(g, subtitle, pad + 48, titleY + 110, W - 2*pad - 96, g.getFont());

        // ── Bottom row: URL + icon ───────────────────────────────────
        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        g.setColor(new Color(0, 229, 255, 160));
        g.drawString("whose.domains  •  Free Domain Tools", pad + 48, H - pad - 36);

        // Decorative cyan circle bottom-right
        g.setColor(new Color(0, 229, 255, 15));
        g.fillOval(W - 200, H - 200, 280, 280);
        g.setColor(new Color(0, 229, 255, 25));
        g.setStroke(new BasicStroke(2f));
        g.drawOval(W - 200, H - 200, 280, 280);

        g.dispose();

        // ── Encode to PNG ────────────────────────────────────────────
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());

        return ResponseEntity.ok().headers(headers).body(baos.toByteArray());
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private String getBadge(String type) {
        switch (type == null ? "" : type) {
            case "tool":   return "🔧  Tool";
            case "blog":   return "📝  Blog";
            case "domain": return "🌐  Domain";
            default:       return null;
        }
    }

    private int chooseFontSize(String text) {
        if (text.length() < 30) return 58;
        if (text.length() < 50) return 48;
        if (text.length() < 70) return 40;
        return 34;
    }

    private void drawWrappedText(Graphics2D g, String text, int x, int y, int maxWidth, Font font) {
        FontMetrics fm = g.getFontMetrics(font);
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int lineHeight = fm.getHeight() + 4;
        int curY = y;

        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(candidate) > maxWidth) {
                if (line.length() > 0) {
                    g.drawString(line.toString(), x, curY);
                    curY += lineHeight;
                    line = new StringBuilder(word);
                } else {
                    g.drawString(candidate, x, curY);
                    curY += lineHeight;
                    line = new StringBuilder();
                }
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            g.drawString(line.toString(), x, curY);
        }
    }
}
