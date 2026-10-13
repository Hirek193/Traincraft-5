package train.common.mtc;



import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.IPeripheralProvider;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

public class BlockATOTransmitterStopPoint extends BlockContainer implements IPeripheralProvider {

   public static BlockATOTransmitterStopPoint instance;


   public BlockATOTransmitterStopPoint(Material p_i45386_1_) {
      super(p_i45386_1_);
      instance = this;


   }

   public IPeripheral getPeripheral(World world, BlockPos pos, EnumFacing side) {
      TileEntity tileEntity = world.getTileEntity(pos);
      return tileEntity instanceof IPeripheral ? (IPeripheral) tileEntity : null;
   }



   @Override
   public TileEntity createNewTileEntity(World p_149915_1_, int p_149915_2_) {
      return new TileATOTransmitterStopPoint();
   }
}
