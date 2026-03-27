package com.cim.block.entity.fluids;

import com.cim.block.entity.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import net.minecraftforge.registries.ForgeRegistries;

public class FluidPipeBlockEntity extends BlockEntity {

    // Свойство для передачи жидкости в рендер (PipeBakedModel)
    public static final ModelProperty<Fluid> FLUID_PROP = new ModelProperty<>();

    private Fluid filterFluid = Fluids.EMPTY;

    public FluidPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FLUID_PIPE_BE.get(), pos, state);
    }

    public void setFilterFluid(Fluid fluid) {
        this.filterFluid = fluid;
        this.setChanged();

        // ПРИКАЗЫВАЕМ МАЙНКРАФТУ ПЕРЕРИСОВАТЬ ТРУБУ (ЭТО ВКЛЮЧИТ ПЯТНА!)
        this.requestModelDataUpdate();

        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 3);
        }
    }

    public Fluid getFilterFluid() {
        return filterFluid;
    }

    // ОТДАЕМ ЖИДКОСТЬ В PipeBakedModel
    @Override
    public ModelData getModelData() {
        return ModelData.builder()
                .with(FLUID_PROP, this.filterFluid)
                .build();
    }

    // --- СОХРАНЕНИЕ В NBT ---
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ResourceLocation fluidKey = ForgeRegistries.FLUIDS.getKey(filterFluid);
        tag.putString("FilterFluid", fluidKey == null ? "minecraft:empty" : fluidKey.toString());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        ResourceLocation fluidKey = new ResourceLocation(tag.getString("FilterFluid"));
        this.filterFluid = ForgeRegistries.FLUIDS.getValue(fluidKey);
        if (this.filterFluid == null) this.filterFluid = Fluids.EMPTY;

        // Обновляем модель при загрузке мира
        this.requestModelDataUpdate();
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // Перехватываем пакет обновления с сервера
    @Override
    public void onDataPacket(net.minecraft.network.Connection net, net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt) {
        // Стандартная загрузка (она прочитает NBT и обновит this.filterFluid внутри load)
        super.onDataPacket(net, pkt);

        // Если мы на клиенте (у игрока) — принудительно заставляем чанк перерисоваться!
        if (this.level != null && this.level.isClientSide) {
            // 1. Обновляем кэш данных
            this.requestModelDataUpdate();
            // 2. Волшебный пинок: говорим движку "Перестрой полигоны этого блока прямо сейчас!"
            this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 3);
        }
    }
}