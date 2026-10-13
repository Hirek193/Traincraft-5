package train.client.core.helpers;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/** 1.12 replacement for the 1.7.10 {@code block.getIcon(side, meta)} lookups used by renderers. */
@SideOnly(Side.CLIENT)
public final class SpriteHelper {
    private SpriteHelper() {
    }

    /** The texture on the given side of a block's model, like {@code block.getIcon(side, meta)} in 1.7.10. */
    @SuppressWarnings("deprecation")
    public static TextureAtlasSprite getSprite(Block block, int meta, EnumFacing side) {
        IBlockState state = block.getStateFromMeta(meta);
        IBakedModel model = Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(state);
        List<BakedQuad> quads = model.getQuads(state, side, 0L);
        if (!quads.isEmpty()) {
            return quads.get(0).getSprite();
        }
        return model.getParticleTexture();
    }

    /** {@code block.getIcon(1, meta)} - the top texture. */
    public static TextureAtlasSprite getTopSprite(Block block, int meta) {
        return getSprite(block, meta, EnumFacing.UP);
    }
}
