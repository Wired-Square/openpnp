package org.openpnp.spi.base;

import javax.swing.Icon;

import org.openpnp.ConfigurationListener;
import org.openpnp.Translations;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;
import org.openpnp.model.RotationSuggestion;
import org.openpnp.model.XmlText;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Nozzle;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

public abstract class AbstractFeeder extends AbstractModelObject implements Feeder {
    /** A note is for a convention or a caveat, not a document. */
    public static final int MAX_NOTE_LENGTH = 2000;

    /**
     * History:
     * 
     * Note: Can't actually use the @Version annotation because of a bug in SimpleXML. See
     * http://sourceforge.net/p/simple/mailman/message/27887562/
     * 
     * 1.0: Initial revision. 
     * 1.1: Migrate retryCount to feedRetryCount and zero out pickRetryCount for initial release
     *      of feature.
     */
    @Attribute(required=false)
    private double version = 1.0;
    
    @Attribute
    protected String id;

    @Attribute(required = false)
    protected String name;

    // Set on the EDT, read by the job processor on the machine thread.
    @Attribute
    protected volatile boolean enabled;

    @Attribute
    protected String partId;
    
    /**
     * Note: This is feedRetryCount in reality. It was left as retryCount for backwards
     * compatibility when pickRetryCount was added. 
     * 
     * TODO Migration has been added and this can be removed after 2021-12-29.  
     */
    @Attribute(required=false)
    protected Integer retryCount = 3;
    
    @Attribute(required=false)
    protected int feedRetryCount = 3;
    
    @Attribute(required = false)
    protected int pickRetryCount = 3;

    protected Part part;

    // Confined to the EDT: set by the Feeders panel and by MCP tools, which run on the EDT.
    @Element(required = false)
    private RotationSuggestion rotationSuggestion;

    // Confined to the EDT: set by the Feeders panel and by MCP tools, which run on the EDT.
    @Element(required = false)
    private String note;

    public AbstractFeeder() {
        this.id = Configuration.createId("FDR");
        this.name = getClass().getSimpleName();
        Configuration.get().addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationLoaded(Configuration configuration) throws Exception {
                part = configuration.getPart(partId);
                
                if (version == 1.0) {
                    feedRetryCount = retryCount;
                    retryCount = null;
                    pickRetryCount = 0;
                    version = 1.1;
                }
            }
        });
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        Object oldValue = this.enabled;
        this.enabled = enabled;
        firePropertyChange("enabled", oldValue, enabled);
    }

    @Override
    public void setPart(Part part) {
        Part oldValue = this.part;
        this.part = part;
        firePropertyChange("part", oldValue, part);
        if (part != null) {
            this.partId = part.getId();
        }
        else {
            this.partId = "";
        }
        // Also notify the old/new part that the feeder count has changed.
        if (oldValue != null) {
            oldValue.setAssignedFeeders(-1);
        }
        if (part != null) {
            part.setAssignedFeeders(+1);
        }
    }

    @Override
    public Part getPart() {
        return part;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
        firePropertyChange("name", null, name);
    }

    @Override
    public String getNote() {
        return note;
    }

    /**
     * A blank note is stored as none. Line endings are stored as \n, which is what XML reads back.
     *
     * @throws IllegalArgumentException if the note is too long or has characters XML can't hold.
     */
    @Override
    public void setNote(String note) {
        String normalised = note == null || note.isBlank() ? null : normaliseLineEndings(note);
        if (normalised != null) {
            if (normalised.length() > MAX_NOTE_LENGTH) {
                throw new IllegalArgumentException(
                        "A note must be at most " + MAX_NOTE_LENGTH + " characters");
            }
            XmlText.requireValid(normalised, "The note");
        }
        Object oldValue = this.note;
        this.note = normalised;
        firePropertyChange("note", oldValue, this.note);
    }

    public static String normaliseLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    @Override
    public RotationSuggestion getRotationSuggestion() {
        return rotationSuggestion;
    }

    @Override
    public void setRotationSuggestion(RotationSuggestion rotationSuggestion) {
        Object oldValue = this.rotationSuggestion;
        this.rotationSuggestion = rotationSuggestion;
        firePropertyChange("rotationSuggestion", oldValue, rotationSuggestion);
    }

    @Override
    public Icon getPropertySheetHolderIcon() {
        return Icons.feeder;
    }

    public int getFeedRetryCount() {
        return feedRetryCount;
    }

    public void setFeedRetryCount(int feedRetryCount) {
        this.feedRetryCount = feedRetryCount;
        firePropertyChange("feedRetryCount", null, feedRetryCount);
    }
    
    public int getPickRetryCount() {
        return pickRetryCount;
    }

    public void setPickRetryCount(int pickRetryCount) {
        this.pickRetryCount = pickRetryCount;
        firePropertyChange("pickRetryCount", null, pickRetryCount);
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        return new PropertySheet[] {new PropertySheetWizardAdapter(getConfigurationWizard(),
                Translations.getString("AbstractFeeder.ConfigurationWizard.title"))}; //$NON-NLS-1$
    }
    
    public void postPick(Nozzle nozzle) throws Exception { }
    
    @Override
    public boolean canTakeBackPart() {
        return false;   // default feeder does not take back parts
    }

    @Override
    public void takeBackPart(Nozzle nozzle) throws Exception {
        throw new UnsupportedOperationException("Not supported on this Feeder");
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        Object oldValue = this.priority;
        this.priority = priority;
        firePropertyChange("priority", oldValue, priority);
    }

    @Attribute(required=false)
    protected Priority priority = Priority.Normal;

}
