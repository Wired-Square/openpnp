package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Abstract2DLocatable.Side;

import com.google.common.io.Files;

public class PlacementsHolderLocationSideTest {
    @BeforeEach
    public void setUp() throws Exception {
        Configuration.initialize(new File(Files.createTempDir(), ".openpnp")); //$NON-NLS-1$
        Configuration.get().load();
    }

    @Test
    public void flippingAChildOfABottomSidePanelReportsTheGlobalSideChange() {
        PanelLocation panelLocation = new PanelLocation(new Panel());
        panelLocation.setGlobalSide(Side.Bottom);
        BoardLocation boardLocation = new BoardLocation(new Board());
        panelLocation.addChild(boardLocation);
        List<Object> sides = new ArrayList<>();
        boardLocation.addPropertyChangeListener("side", e -> sides.add(e.getNewValue())); //$NON-NLS-1$

        boardLocation.setGlobalSide(Side.Top);

        assertEquals(Side.Top, boardLocation.getGlobalSide());
        assertEquals(List.of(Side.Top), sides);
    }
}
