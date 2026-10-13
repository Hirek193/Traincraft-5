package train.client.render.obj;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.obj.OBJModel;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import train.common.Traincraft;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drop-in replacement for the 1.7.10 {@code IModelCustom} / {@code AdvancedModelLoader}
 * (removed from Forge in 1.8), built on Forge 1.12's own OBJ loader ({@link OBJModel}).
 * <p>
 * Usage stays as in 1.7.10: bind a texture, set up the GL transforms, then call
 * {@link #renderAll()} or {@link #renderPart(String)}.
 * <p>
 * How it works: the .obj is parsed and baked by Forge, using a sprite that covers the whole
 * 0..1 UV range instead of a texture atlas slot, so the quads keep the model's original UVs
 * and render with whatever texture is currently bound - exactly like the old immediate-mode
 * renderer. The baked quads are cached per group and drawn through the Tessellator
 * (position/uv/normal only, so {@code glColor} tinting still works).
 * <p>
 * Traincraft's 1.7.10 .obj files are cleaned up in memory before Forge parses them:
 * {@code mtllib}/{@code usemtl} lines are dropped (most .mtl files were never shipped and the
 * old loader ignored them), and faces with more than 4 vertices are split into a triangle fan
 * (the old loader drew them as GL_POLYGON; Forge only accepts triangles and quads).
 */
@SideOnly(Side.CLIENT)
public final class TCObjModel {
    private static final VertexFormat FORMAT = DefaultVertexFormats.POSITION_TEX_NORMAL;
    private static final Map<ResourceLocation, TCObjModel> CACHE = new HashMap<>();
    private static final FullTextureSprite FULL_SPRITE = new FullTextureSprite();

    private final ResourceLocation location;
    @Nullable
    private OBJModel model;
    private boolean failed;
    @Nullable
    private List<BakedQuad> allQuads;
    private final Map<String, List<BakedQuad>> partQuads = new HashMap<>();

    private TCObjModel(ResourceLocation location) {
        this.location = location;
    }

    /** Replacement for {@code TCObjModel.load(location)}. Loading is lazy. */
    public static TCObjModel load(ResourceLocation location) {
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(location, TCObjModel::new);
        }
    }

    /** Forget all parsed/baked data, e.g. after a resource pack reload. */
    public static void clearCache() {
        synchronized (CACHE) {
            for (TCObjModel m : CACHE.values()) {
                m.model = null;
                m.failed = false;
                m.allQuads = null;
                m.partQuads.clear();
            }
        }
    }

    public ResourceLocation getLocation() {
        return this.location;
    }

    public void renderAll() {
        if (this.allQuads == null) {
            this.allQuads = bake(Collections.singletonList(OBJModel.Group.ALL));
        }
        draw(this.allQuads);
    }

    public void renderPart(String partName) {
        List<BakedQuad> quads = this.partQuads.get(partName);
        if (quads == null) {
            quads = bake(Collections.singletonList(partName));
            this.partQuads.put(partName, quads);
        }
        draw(quads);
    }

    public void renderOnly(String... groupNames) {
        for (String name : groupNames) {
            renderPart(name);
        }
    }

    public void renderAllExcept(String... excludedGroupNames) {
        OBJModel obj = getModel();
        if (obj == null) {
            return;
        }
        Set<String> excluded = new HashSet<>(Arrays.asList(excludedGroupNames));
        for (String name : obj.getMatLib().getGroups().keySet()) {
            if (!excluded.contains(name)) {
                renderPart(name);
            }
        }
    }

    @Nullable
    private OBJModel getModel() {
        if (this.model == null && !this.failed) {
            try {
                IResourceManager manager = Minecraft.getMinecraft().getResourceManager();
                try (IResource original = manager.getResource(this.location)) {
                    byte[] cleaned = cleanUp(original.getInputStream());
                    this.model = new OBJModel.Parser(new MemoryResource(original, cleaned), manager).parse();
                }
            } catch (Exception e) {
                this.failed = true;
                Traincraft.tcLog.error("Could not load OBJ model " + this.location, e);
            }
        }
        return this.model;
    }

    private List<BakedQuad> bake(List<String> visibleGroups) {
        OBJModel obj = getModel();
        if (obj == null) {
            return Collections.emptyList();
        }
        try {
            IBakedModel baked = obj.bake(new OBJModel.OBJState(visibleGroups, true), FORMAT, rl -> FULL_SPRITE);
            return new ArrayList<>(baked.getQuads(null, null, 0L));
        } catch (Exception e) {
            Traincraft.tcLog.error("Could not bake OBJ model " + this.location + " groups " + visibleGroups, e);
            return Collections.emptyList();
        }
    }

    private static void draw(List<BakedQuad> quads) {
        if (quads.isEmpty()) {
            return;
        }
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, FORMAT);
        for (BakedQuad quad : quads) {
            buffer.addVertexData(quad.getVertexData());
        }
        tessellator.draw();
    }

    /** Keeps v/vt/vn/f/o/g lines, drops material lines, fan-triangulates faces with more than 4 vertices. */
    static byte[] cleanUp(InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] tokens = trimmed.split("\\s+");
                String key = tokens[0];
                if (key.equals("f") && tokens.length > 5) {
                    for (int i = 2; i < tokens.length - 1; i++) {
                        out.append("f ").append(tokens[1]).append(' ').append(tokens[i]).append(' ').append(tokens[i + 1]).append('\n');
                    }
                } else if (key.equals("v") || key.equals("vt") || key.equals("vn") || key.equals("f") || key.equals("o") || key.equals("g")) {
                    out.append(trimmed).append('\n');
                }
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A sprite spanning the whole texture, so baked UVs equal the model's UVs. */
    private static final class FullTextureSprite extends TextureAtlasSprite {
        FullTextureSprite() {
            super("tc:obj_full_texture");
            setIconWidth(1);
            setIconHeight(1);
            initSprite(1, 1, 0, 0, false);
        }
    }

    /** The cleaned-up .obj text, presented to Forge's parser as a resource. */
    private static final class MemoryResource implements IResource {
        private final ResourceLocation location;
        private final String packName;
        private final byte[] data;

        MemoryResource(IResource original, byte[] data) {
            this.location = original.getResourceLocation();
            this.packName = original.getResourcePackName();
            this.data = data;
        }

        @Override
        public ResourceLocation getResourceLocation() {
            return this.location;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(this.data);
        }

        @Override
        public boolean hasMetadata() {
            return false;
        }

        @Nullable
        @Override
        public <T extends IMetadataSection> T getMetadata(String sectionName) {
            return null;
        }

        @Override
        public String getResourcePackName() {
            return this.packName;
        }

        @Override
        public void close() {
        }
    }
}
