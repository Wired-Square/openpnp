package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.feeder.ReferenceSlotAutoFeeder;
import org.openpnp.machine.reference.feeder.ReferenceSlotAutoFeeder.Bank;
import org.openpnp.spi.Feeder;

import com.google.common.io.Files;

public class PartUsersTest {
    private static final String CONFIG = "config/BasicJobTest/"; //$NON-NLS-1$
    private static final String PART_ID = "R-0805-10K"; //$NON-NLS-1$
    private static final String FEEDER_ID = "F1"; //$NON-NLS-1$
    private static final String BOARD_NAME = "Test board"; //$NON-NLS-1$
    private static final String BANK_FEEDER_NAME = "Bank feeder"; //$NON-NLS-1$

    private Configuration configuration;
    private Part part;
    private Feeder feeder;

    @BeforeEach
    public void setUp() throws Exception {
        File directory = new File(Files.createTempDir(), ".openpnp"); //$NON-NLS-1$
        for (String file : new String[] {"machine.xml", "packages.xml", "parts.xml"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            FileUtils.copyURLToFile(ClassLoader.getSystemResource(CONFIG + file),
                    new File(directory, file));
        }
        Configuration.initialize(directory);
        configuration = Configuration.get();
        configuration.load();
        part = configuration.getPart(PART_ID);
        feeder = configuration.getMachine().getFeeder(FEEDER_ID);
    }

    @Test
    public void aFeederHoldingThePartIsAUser() {
        feeder.setPart(part);

        assertEquals(List.of(feeder.getName()), configuration.getPartUsers(part));
    }

    @Test
    public void aLoadedBoardWithAPlacementOfThePartIsAUser() {
        feeder.setPart(null);
        Board board = new Board();
        board.setName(BOARD_NAME);
        Placement placement = new Placement("R1"); //$NON-NLS-1$
        placement.setPart(part);
        board.addPlacement(placement);
        configuration.addBoard(board);

        assertEquals(List.of(BOARD_NAME), configuration.getPartUsers(part));
    }

    @Test
    public void aFeederAndABoardAreBothUsers() {
        feeder.setPart(part);
        Board board = new Board();
        board.setName(BOARD_NAME);
        Placement placement = new Placement("R1"); //$NON-NLS-1$
        placement.setPart(part);
        board.addPlacement(placement);
        configuration.addBoard(board);

        assertEquals(List.of(feeder.getName(), BOARD_NAME), configuration.getPartUsers(part));
    }

    @Test
    public void aBankFeederNotLoadedInASlotIsAUser() throws Exception {
        feeder.setPart(null);
        configuration.getMachine().addFeeder(new ReferenceSlotAutoFeeder());
        Bank bank = ReferenceSlotAutoFeeder.getBanks().get(0);
        ReferenceSlotAutoFeeder.Feeder bankFeeder = new ReferenceSlotAutoFeeder.Feeder();
        bankFeeder.setName(BANK_FEEDER_NAME);
        bankFeeder.setPart(part);
        bank.getFeeders().add(bankFeeder);

        assertEquals(List.of(bank.getName() + " / " + BANK_FEEDER_NAME), //$NON-NLS-1$
                configuration.getPartUsers(part));
    }

    @Test
    public void anUnusedPartHasNoUsers() {
        feeder.setPart(null);

        assertTrue(configuration.getPartUsers(part).isEmpty());
    }
}
