package net.conczin.mca.client.render;

import net.conczin.mca.client.model.VillagerEntityModelMCA;
import net.conczin.mca.client.render.layer.ClothingLayer;
import net.conczin.mca.client.render.layer.FaceLayer;
import net.conczin.mca.client.render.layer.HairLayer;
import net.conczin.mca.client.render.layer.SkinLayer;
import net.conczin.mca.client.render.layer.VillagerFishingLineLayer;
import net.conczin.mca.entity.MCAFishingBobberEntity;
import net.conczin.mca.entity.VillagerEntityMCA;
import net.conczin.mca.MCAClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.item.FishingRodItem;
import org.jetbrains.annotations.Nullable;

public class VillagerEntityMCARenderer extends VillagerLikeEntityMCARenderer<VillagerEntityMCA> {
    private static final double BOBBER_SEARCH_RADIUS = 32.0;

    public VillagerEntityMCARenderer(EntityRendererProvider.Context ctx) {
        super(ctx, createAnimationModel(ctx).hideWears());

        layers.add(0, new SkinLayer<>(this, createVisibleModel(VillagerEntityModelMCA.bodyData(CubeDeformation.NONE)).hideWears()));
        addLayer(new FaceLayer<>(this, createVisibleModel(VillagerEntityModelMCA.bodyData(new CubeDeformation(0.01F))).hideWears(), "normal"));
        addLayer(new ClothingLayer<>(this, createVisibleModel(VillagerEntityModelMCA.bodyData(new CubeDeformation(0.0625F))), "normal"));
        addLayer(new HairLayer<>(this, createVisibleModel(VillagerEntityModelMCA.hairData(new CubeDeformation(0.125F)))));
        addLayer(new VillagerFishingLineLayer(this));
    }

    @Override
    public void extractRenderState(VillagerEntityMCA entity, VillagerRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        String home = entity.getResidency().getVillageName();
        state.villageName = MCAClient.showVillageNames && !home.isBlank() ? home : null;
        MCAFishingBobberEntity bobber = findOwnedBobber(entity);
        state.fishingHookPosition = bobber == null
                ? null
                : bobber.getPosition(partialTicks).add(0.0, 0.25, 0.0);
    }

    @Override
    protected void submitNameDisplay(VillagerRenderState state, PoseStack matrices,
                                     SubmitNodeCollector collector, CameraRenderState camera) {
        super.submitNameDisplay(state, matrices, collector, camera);
        if (state.villageName == null || state.nameTag == null || state.nameTagAttachment == null) return;
        Component name = state.nameTag;
        Vec3 attachment = state.nameTagAttachment;
        state.nameTag = Component.literal(state.villageName).withStyle(ChatFormatting.GRAY);
        state.nameTagAttachment = attachment.add(0, -0.25D, 0);
        super.submitNameDisplay(state, matrices, collector, camera);
        state.nameTag = name;
        state.nameTagAttachment = attachment;
    }

    @Nullable
    private static MCAFishingBobberEntity findOwnedBobber(VillagerEntityMCA villager) {
        if (!(villager.getItemInHand(villager.getDominantHand()).getItem() instanceof FishingRodItem)) {
            return null;
        }

        return villager.level()
                .getEntitiesOfClass(
                        MCAFishingBobberEntity.class,
                        villager.getBoundingBox().inflate(BOBBER_SEARCH_RADIUS),
                        bobber -> !bobber.isRemoved() && bobber.getVillagerOwner() == villager
                )
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static VillagerEntityModelMCA createAnimationModel(EntityRendererProvider.Context ctx) {
        return new VillagerEntityModelMCA(ctx.bakeLayer(ModelLayers.PLAYER));
    }

    private static VillagerEntityModelMCA createVisibleModel(MeshDefinition data) {
        VillagerEntityModelMCA model = new VillagerEntityModelMCA(LayerDefinition.create(data, 64, 64).bakeRoot());
        model.receiveDeferredAnimationPose();
        return model;
    }
}
