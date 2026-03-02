package razchexlitiel.cim.client.gecko.item.rotation;


import razchexlitiel.cim.item.rotation.DrillHeadItem;
import razchexlitiel.cim.item.rotation.MotorElectroBlockItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class DrillHeadItemRenderer extends GeoItemRenderer<DrillHeadItem> {
    public DrillHeadItemRenderer() {
        super(new DrillHeadItemModel());
    }
}