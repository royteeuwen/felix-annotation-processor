package net.chilicat.felixscr.intellij.settings;


import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;


@State(name = "ScrSettings",
        storages = {
                @Storage(file = "$PROJECT_FILE$"),
                @Storage(file = "$PROJECT_CONFIG_DIR$/felix-scr.xml", scheme = StorageScheme.DIRECTORY_BASED)
        }
)
public class ScrSettings implements PersistentStateComponent<ScrSettings> {

    public static final String SPEC_1_0 = "1.0";
    public static final String SPEC_1_1 = "1.1";

    private boolean enabled = true;
    private boolean strictMode = true;
    private String spec = SPEC_1_1;
    private ManifestPolicy manifestPolicy = ManifestPolicy.overwrite;

    public String getSpec() {
        return spec;
    }

    public void setSpec(String spec) {
        this.spec = spec;
    }

    public boolean isStrictMode() {
        return strictMode;
    }

    public void setStrictMode(boolean strictMode) {
        this.strictMode = strictMode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ScrSettings getState() {
        return this;
    }

    public void loadState(ScrSettings scrSettings) {
        XmlSerializerUtil.copyBean(scrSettings, this);
    }

    @NotNull
    public static ScrSettings getInstance(@NotNull Project project) {
        return ServiceManager.getService(project, ScrSettings.class);
    }

    public boolean isSpec(String spec) {
        return spec.equals(getSpec());
    }

    public void setManifestPolicy(ManifestPolicy policy) {
        this.manifestPolicy = policy;
    }

    public ManifestPolicy getManifestPolicy() {
        return manifestPolicy;
    }
}