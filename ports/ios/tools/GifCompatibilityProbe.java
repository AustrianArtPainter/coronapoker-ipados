import com.tonikelope.coronapoker.ios.PortableGif;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;

/** Differential check against the desktop's real ImageIO GIF decoding. */
public final class GifCompatibilityProbe {
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static Node child(Node root, String name) {
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling())
            if (name.equals(node.getNodeName())) return node;
        return null;
    }
    private static String attribute(Node node, String name, String fallback) {
        if (node == null || node.getAttributes().getNamedItem(name) == null) return fallback;
        return node.getAttributes().getNamedItem(name).getNodeValue();
    }
    public static void main(String[] args) throws Exception {
        List<Path> paths;
        try (var files = Files.walk(Path.of(args[0]))) {
            paths = files.filter(p -> p.toString().endsWith(".gif")).sorted().toList();
        }
        long verifiedFrames = 0;
        for (Path path : paths) {
            byte[] data = Files.readAllBytes(path);
            var portable = PortableGif.inspect(data);
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
                try {
                    reader.setInput(input, false, false);
                    require(reader.getNumImages(true) == portable.frames().size(), path + " frame count");
                    Node stream = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
                    Node extent = child(stream, "LogicalScreenDescriptor");
                    require(portable.width() == Integer.parseInt(attribute(extent, "logicalScreenWidth", "0")), path + " canvas width");
                    require(portable.height() == Integer.parseInt(attribute(extent, "logicalScreenHeight", "0")), path + " canvas height");
                    for (int index = 0; index < portable.frames().size(); index++) {
                        var frame = portable.frames().get(index);
                        Node metadata = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0");
                        Node control = child(metadata, "GraphicControlExtension");
                        int delay = Integer.parseInt(attribute(control, "delayTime", "10"));
                        require(frame.delayMillis() == (delay <= 1 ? 100 : delay * 10), path + " timing " + index);
                        require(frame.width() == reader.getWidth(index) && frame.height() == reader.getHeight(index), path + " frame extent " + index);
                    }
                    BufferedImage canvas = new BufferedImage(portable.width(), portable.height(), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = canvas.createGraphics();
                    int[] index = {0};
                    try {
                        PortableGif.decode(data, portable.width(), actual -> {
                            int frameIndex = index[0]++;
                            var frame = portable.frames().get(frameIndex);
                            BufferedImage previous = new BufferedImage(portable.width(), portable.height(), BufferedImage.TYPE_INT_ARGB);
                            previous.setRGB(0, 0, portable.width(), portable.height(), canvas.getRGB(0, 0, portable.width(), portable.height(), null, 0, portable.width()), 0, portable.width());
                            try {
                                BufferedImage raw = reader.read(frameIndex);
                                graphics.drawImage(raw, frame.left(), frame.top(), null);
                                int[] expected = canvas.getRGB(0, 0, portable.width(), portable.height(), null, 0, portable.width());
                                for (int pixel = 0; pixel < expected.length; pixel++) {
                                    int offset = pixel * 4;
                                    int color = (actual.rgba()[offset + 3] & 255) << 24 | (actual.rgba()[offset] & 255) << 16
                                            | (actual.rgba()[offset + 1] & 255) << 8 | (actual.rgba()[offset + 2] & 255);
                                    if (expected[pixel] != color) throw new IllegalStateException(path + " pixels differ: frame " + frameIndex + " pixel " + pixel);
                                }
                                if (frame.disposal() == 2) {
                                    graphics.setComposite(AlphaComposite.Clear);
                                    graphics.fillRect(frame.left(), frame.top(), frame.width(), frame.height());
                                    graphics.setComposite(AlphaComposite.SrcOver);
                                } else if (frame.disposal() == 3) {
                                    graphics.setComposite(AlphaComposite.Src);
                                    graphics.drawImage(previous, 0, 0, null);
                                    graphics.setComposite(AlphaComposite.SrcOver);
                                }
                            } catch (Exception failure) { throw new RuntimeException(failure); }
                        }, () -> index[0] >= 8);
                    } finally { graphics.dispose(); }
                    verifiedFrames += index[0];
                } finally { reader.dispose(); }
            }
        }
        System.out.println("{\"gifFiles\":" + paths.size() + ",\"metadataAllFrames\":true,\"exactCompositedPixelFrames\":" + verifiedFrames + "}");
    }
}
