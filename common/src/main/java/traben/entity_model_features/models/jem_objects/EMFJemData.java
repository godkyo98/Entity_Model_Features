package traben.entity_model_features.models.jem_objects;

import org.jetbrains.annotations.Nullable;
import traben.entity_model_features.EMF;
import traben.entity_model_features.EMFException;
import traben.entity_model_features.utils.EMFDirectoryHandler;
import traben.entity_model_features.models.EMFModelMappings;
import traben.entity_model_features.utils.EMFUtils;
import traben.entity_model_features.models.EMFModel_ID;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

@SuppressWarnings("CanBeFinal")
public class EMFJemData {

    private final LinkedHashMap<String, List<LinkedHashMap<String, String>>> allTopLevelAnimationsByVanillaPartName = new LinkedHashMap<>();
    public String texture = "";
    public int[] textureSize = null;
    public double shadow_size = 1.0;
    public LinkedList<EMFPartData> models = new LinkedList<>();
    public EMFDirectoryHandler directoryContext = null;
    private EMFModel_ID mobModelIDInfo = null;
    private ResourceLocation customTexture = null;

    public LinkedHashMap<String, List<LinkedHashMap<String, String>>> getAllTopLevelAnimationsByVanillaPartName() {
        return allTopLevelAnimationsByVanillaPartName;
    }

    public EMFModel_ID getMobModelIDInfo() {
        return mobModelIDInfo;
    }

    public ResourceLocation getCustomTexture() {
        return customTexture;
    }

    @Nullable
    public ResourceLocation validateJemTexture(String textureIn) {
        return validateJemTexture(textureIn, false);
    }

    @Nullable
    public ResourceLocation validateJemTexture(String textureIn, boolean canRemoveRedundancy) {
        if (textureIn == null || textureIn.isBlank()) return null;
/*
OptiFine spec

# Textures can be specified as:
#   "texture" - (no '/' in name), look in current folder
#   "./folder/texture" - relative to current folder
#   "~/folder/texture" - relative to folder "assets/minecraft/optifine/"
#   "folder/texture" - relative to folder "assets/minecraft/"
#   "mod:folder/texture - resolves as "assets/mod/folder/texture.png"
*/

        String textureTest = textureIn.trim();
        if (!textureTest.isBlank()) {
            if (!textureTest.endsWith(".png")) textureTest += ".png";

            if (!textureTest.contains(":")) {
                //if no folder parenting assume it is relative to model
                if (!textureTest.contains("/")) {
                    textureTest = directoryContext.getRelativeDirectoryLocationNoValidation(textureTest);
                } else if (textureTest.startsWith("./")) {
                    textureTest = directoryContext.getRelativeDirectoryLocationNoValidation(textureTest.replaceFirst("\\./", ""));
                } else if (textureTest.startsWith("~/")) {
                    textureTest = "optifine/" + textureTest.replaceFirst("~/", "");
                }//else it is a full path
            }//else it is a full path

            //test if it is a redundant texture to reduce texture overrides during rendering
            if(canRemoveRedundancy && "minecraft".equals(directoryContext.namespace) //is vanilla model
                    && (!textureTest.contains(":") || textureTest.startsWith("minecraft:"))){//is vanilla texture
                textureTest = textureTest.startsWith("minecraft:") ? textureTest : "minecraft:" + textureTest;

                if (textureTest.equals(EMFModelMappings.DEFAULT_TEXTURE_MAPPINGS.get(directoryContext.rawFileName))) {
                    if (EMF.config().getConfig().logModelCreationData)
                        EMFUtils.log("Removing redundant texture: " + textureTest + " declared in " + directoryContext.getFileNameWithType());
                    return null;
                }
            }

            if (
                #if MC >= MC_21
                    ResourceLocation.tryParse(textureTest) != null
                #else
                    ResourceLocation.isValidResourceLocation(textureTest)
                #endif
            ) {
                ResourceLocation possibleTexture = EMFUtils.res(textureTest);
                if (Minecraft.getInstance().getResourceManager().getResource(possibleTexture).isPresent()) {
                    return possibleTexture;
                }
            } else {
                EMFUtils.logWarn("Invalid texture identifier: " + textureTest + " for " + directoryContext.getFileNameWithType());
            }
        }
        return MissingTextureAtlasSprite.getLocation();
    }


    public void prepare(EMFDirectoryHandler directoryContext, EMFModel_ID mobModelIDInfo) {
        try {
            this.directoryContext = directoryContext;
            this.mobModelIDInfo = mobModelIDInfo;

            if (textureSize != null && textureSize.length != 2) {
                textureSize = new int[]{64, 32};
                EMFUtils.logWarn("No textureSize provided for: " + directoryContext.getFileNameWithType() + ". Defaulting to 64x32 texture size for model.");
            }

            LinkedList<EMFPartData> originalModelsForReadingOnly = new LinkedList<>(models);

            customTexture = validateJemTexture(texture, true);

//            String mapId = mobModelIDInfo.getMapId();
            Map<String, String> map = EMFModelMappings.getMapOf(mobModelIDInfo, null);


            //change all part values to their vanilla counterparts
            for (EMFPartData partData : models) {
                if (partData.part != null && map.containsKey(partData.part)) {
                    partData.originalPart = partData.part;
                    partData.part = map.get(partData.part);
                }
            }

            for (EMFPartData model : models) {
                model.prepare(textureSize, this);
            }

            ///prep animations
            SortedMap<String, EMFPartData> alphabeticalOrderedParts = new TreeMap<>(Comparator.naturalOrder());
            if (EMF.config().getConfig().logModelCreationData)
                EMFUtils.log("originalModelsForReadingOnly #= " + originalModelsForReadingOnly.size());

            for (EMFPartData partData :
                    originalModelsForReadingOnly) {
                //if two parts both with id of EMF_body the later will get renamed to copy first come first serve approach that optifine seems to have
                String newId = EMFUtils.getIdUnique(alphabeticalOrderedParts.keySet(), partData.id);
                if (!newId.equals(partData.id)) partData.id = newId;
                alphabeticalOrderedParts.put(partData.id, partData);
            }

            if (EMF.config().getConfig().logModelCreationData)
                EMFUtils.log("alphabeticalOrderedParts = " + alphabeticalOrderedParts);

            for (EMFPartData part : alphabeticalOrderedParts.values()) {
                if (part.animations != null) {
                    var list = new LinkedList<LinkedHashMap<String, String>>();
                    for (LinkedHashMap<String, String> animation : part.animations) {
                        LinkedHashMap<String, String> thisPartsAnimations = new LinkedHashMap<>();
                        animation.forEach((key, anim) -> {
                            key = key.trim().replaceAll("\\s", "");
                            anim = anim.trim().replaceAll("\\s", "");
                            //replace "this"
                            if (key.contains("this")) key = key.replaceFirst("(?<=\\W|^)this(?=\\W)", part.id);
                            if (anim.contains("this")) anim = anim.replaceAll("(?<=\\W|^)this(?=\\W)", part.id);
                            //replace "part"
                            if (key.contains("part")) key = key.replaceFirst("(?<=\\W|^)part(?=\\W)", Objects.requireNonNullElse(part.originalPart, part.part));
                            if (anim.contains("part")) anim = anim.replaceAll("(?<=\\W|^)part(?=\\W)", Objects.requireNonNullElse(part.originalPart, part.part));

                            if (!key.isBlank() && !anim.isBlank())
                                thisPartsAnimations.put(key, anim);
                        });
                        if (!thisPartsAnimations.isEmpty()) {
                            list.add(thisPartsAnimations);
                        }
                    }
                    if (!list.isEmpty()) {
                        allTopLevelAnimationsByVanillaPartName
                                .computeIfAbsent(part.part, k -> new LinkedList<>())
                                .addAll(list);
                    }
                }
            }

            //place in a simple animation to set the shadow size
            if (shadow_size != 1.0) {
                shadow_size = Math.max(shadow_size, 0);
                var shadowAnim = new LinkedHashMap<String, String>();
                shadowAnim.put("render.shadow_size", String.valueOf(shadow_size));
                allTopLevelAnimationsByVanillaPartName
                        .computeIfAbsent("root", k -> new LinkedList<>())
                        .add(shadowAnim);
            }
        } catch (Exception e) {
            String message = "Error preparing jem data, for model [" + mobModelIDInfo.getDisplayFileName() + "]: " + e.getMessage();
            EMFUtils.logError(message);
            throw EMFException.recordException(new RuntimeException(message));
        }
        ///finished animations preprocess
    }


    @Override
    public String toString() {
        return "EMF_JemData{" +
                "texture='" + texture + '\'' +
                ", textureSize=" + Arrays.toString(textureSize) +
                ", shadow_size=" + shadow_size +
                ", models=" + models.toString() +
                '}';
    }

}
