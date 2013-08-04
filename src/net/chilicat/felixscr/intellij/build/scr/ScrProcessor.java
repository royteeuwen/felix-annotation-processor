package net.chilicat.felixscr.intellij.build.scr;

import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PathUtil;
import net.chilicat.felixscr.intellij.build.ScrCompiler;
import net.chilicat.felixscr.intellij.settings.ScrSettings;
import org.apache.felix.scrplugin.*;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.Manifest;

public class ScrProcessor {
    private final CompileContext context;
    private final Module module;
    private final String outputDir;
    private ScrSettings settings;

    public ScrProcessor(CompileContext context, Module module, String outputDir) {
        this.context = context;
        this.module = module;
        this.outputDir = outputDir;
    }

    public CompileContext getContext() {
        return context;
    }

    public Module getModule() {
        return module;
    }

    public String getOutputDir() {
        return outputDir;
    }


    public void setSettings(ScrSettings settings) {
        this.settings = settings;
    }

    public boolean execute() {
        final ScrLogger logger = new ScrLogger(this.getContext(), module);

        try {
            final File classDir = new File(this.getOutputDir());

            final Collection<String> classPath = new LinkedHashSet<String>();
            classPath.add(classDir.getPath());
            classPath.add(PathUtil.getJarPathForClass(Component.class));
            classPath.add(PathUtil.getJarPathForClass(BundleContext.class));
            collectClasspath(this.getModule(), classPath);

            Options opt = new Options();
            opt.setGenerateAccessors(settings.isGenerateAccessors());
            opt.setSpecVersion(SpecVersion.fromName(settings.getSpec()));
            opt.setStrictMode(settings.isStrictMode());
            opt.setProperties(new HashMap<String, String>());
            opt.setOutputDirectory(classDir);

            Project project = new Project();
            project.setClassLoader(createClassLoader(classPath));
            project.setClassesDirectory(classDir.getAbsolutePath());
            project.setSources(getSources());
            project.setDependencies(toFileCollection(classPath));

            SCRDescriptorGenerator gen = new SCRDescriptorGenerator(logger);
            gen.setOptions(opt);
            gen.setProject(project);

            Result result = gen.execute();
            if (result.getScrFiles() != null) {
                updateManifest(result, logger);
            }

            return !logger.isErrorPrinted();

        } catch (SCRDescriptorFailureException e) {
            logger.error("Module [" + module.getName() + "]: " + e.getMessage(), e);
        } catch (SCRDescriptorException e) {
            logger.error("Module [" + module.getName() + "]: " + e.getMessage(), e.getSourceLocation(), 0);
        } catch (MalformedURLException e) {
            logger.error("Module [" + module.getName() + "]: " + e.getMessage(), e);
        }
        return false;
    }

    private Collection<Source> getSources() {
        final Collection<Source> sources;


        if (settings.isScanClasses()) {
            VirtualFile out = context.getModuleOutputDirectory(module);
            if (out != null) {
                sources = ScrSource.toSourcesCollection(new VirtualFile[]{out}, ".class");
            } else {
                sources = Collections.emptyList();
            }
        } else {
            final VirtualFile[] sourceRoots = ModuleRootManager.getInstance(this.getModule()).getSourceRoots(false);
            sources = ScrSource.toSourcesCollection(sourceRoots, ".java");
        }
        return sources;
    }

    private Collection<File> toFileCollection(Collection<String> classPath) {
        Collection<File> files = new ArrayList<File>(classPath.size());
        boolean first = true;
        for (String a : classPath) {
            if (!first) {
                files.add(new File(a));
            }
            first = false;
        }
        return files;
    }


    private void updateManifest(Result result, ScrLogger logger) {
        File manifest = new File(this.getOutputDir(), "/META-INF/MANIFEST.MF");
        if (manifest.exists() && !result.getScrFiles().isEmpty()) {
            final String componentLine = "OSGI-INF/*";

            try {
                FileInputStream in = new FileInputStream(manifest);
                Manifest m = null;
                try {
                    m = new Manifest(in);
                    switch (settings.getManifestPolicy()) {
                        case overwrite:
                            m.getMainAttributes().putValue("Service-Component", componentLine);
                            break;
                        case merge:
                            String value = m.getMainAttributes().getValue("Service-Component");
                            if (value == null || value.isEmpty()) {
                                m.getMainAttributes().putValue("Service-Component", componentLine);
                            } else {
                                m.getMainAttributes().putValue("Service-Component", addServiceComponentTo(value, componentLine));
                            }

                            break;
                    }
                } finally {
                    in.close();
                }

                FileOutputStream out = new FileOutputStream(manifest);
                try {
                    m.write(out);
                } finally {
                    out.close();
                }

            } catch (IOException e) {
                logger.error(e);
            }
        } else {
            logger.warn("Module '" + module.getName() + "' has no manifest. Couldn't add component descriptor");
        }
    }

    private String addServiceComponentTo(String value, String serviceComponentXml) {
        String[] values = value.split(",");
        Set<String> all = new HashSet<String>();
        for (String v : values) {
            all.add(v.trim());
        }
        all.add(serviceComponentXml);

        StringBuilder finalValue = new StringBuilder();
        for (String a : all) {
            if (finalValue.length() > 0) {
                finalValue.append(",");
            }
            finalValue.append(a);
        }
        return finalValue.toString();
    }

    private ClassLoader createClassLoader(Collection<String> classPath) throws MalformedURLException {
        final URL[] urls = new URL[classPath.size()];
        final List<String> list = new ArrayList<String>(classPath);

        for (int i = 0; i < classPath.size(); i++) {
            urls[i] = new File(list.get(i)).toURI().toURL();
        }

        return new URLClassLoader(urls, getClass().getClassLoader());
    }

    private void collectClasspath(Module module, Collection<String> classPath) {
        for (OrderEntry library : ModuleRootManager.getInstance(module).getOrderEntries()) {
            if (library instanceof LibraryOrderEntry) {
                LibraryOrderEntry libEntry = (LibraryOrderEntry) library;
                if (libEntry.getScope().isForProductionCompile() || libEntry.getScope().isForProductionRuntime()) {
                    final Library lib = libEntry.getLibrary();

                    if (lib != null) {
                        final VirtualFile[] files = lib.getFiles(OrderRootType.CLASSES);
                        for (VirtualFile f : files) {
                            classPath.add(VfsUtil.virtualToIoFile(f).getAbsolutePath());
                        }
                    }
                }
            }
        }

        for (Module m : ModuleRootManager.getInstance(module).getDependencies()) {
            String outputPath = ScrCompiler.getOutputPath(getContext(), m);
            if (!classPath.contains(outputPath)) {
                classPath.add(outputPath);
                collectClasspath(m, classPath);
            }
        }
    }

}
