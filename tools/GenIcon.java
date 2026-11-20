import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

public class GenIcon {
    public static void main(String[] args) throws Exception {
        int[] sizes = {48, 72, 96, 144, 192};
        String[] dirs = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        String base = args[0];
        for (int i = 0; i < sizes.length; i++) {
            BufferedImage img = draw(sizes[i]);
            File out = new File(base + "/mipmap-" + dirs[i] + "/ic_launcher.png");
            out.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(img, "png", out);
        }
    }

    static BufferedImage draw(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1565C0));
        g.fillRoundRect(0, 0, s, s, (int)(s*0.22), (int)(s*0.22));
        float u = s / 100f;
        // 电池外壳
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(Math.max(2f, 5f*u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawRoundRect((int)(20*u), (int)(30*u), (int)(58*u), (int)(42*u), (int)(6*u), (int)(6*u));
        g.fillRoundRect((int)(79*u), (int)(44*u), (int)(7*u), (int)(14*u), (int)(2*u), (int)(2*u));
        // 电量
        g.setColor(new Color(0x00E676));
        g.fillRoundRect((int)(26*u), (int)(36*u), (int)(34*u), (int)(30*u), (int)(2*u), (int)(2*u));
        // 闪电
        g.setColor(new Color(0xFFD600));
        Polygon p = new Polygon();
        p.addPoint((int)(56*u), (int)(30*u));
        p.addPoint((int)(42*u), (int)(55*u));
        p.addPoint((int)(52*u), (int)(55*u));
        p.addPoint((int)(46*u), (int)(74*u));
        p.addPoint((int)(66*u), (int)(48*u));
        p.addPoint((int)(55*u), (int)(48*u));
        g.fillPolygon(p);
        g.dispose();
        return img;
    }
}
