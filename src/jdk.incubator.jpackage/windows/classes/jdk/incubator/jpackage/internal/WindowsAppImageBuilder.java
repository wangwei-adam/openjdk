/*
 * Copyright (c) 2015, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.incubator.jpackage.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ResourceBundle;
import static jdk.incubator.jpackage.internal.OverridableResource.createResource;

import static jdk.incubator.jpackage.internal.StandardBundlerParam.*;

public class WindowsAppImageBuilder extends AbstractAppImageBuilder {
    private static final ResourceBundle I18N = ResourceBundle.getBundle(
            "jdk.incubator.jpackage.internal.resources.WinResources");

    private static final String TEMPLATE_APP_ICON ="java48.ico";

    private final Path root;
    private final Path appDir;
    private final Path appModsDir;
    private final Path runtimeDir;
    private final Path mdir;
    private final Path binDir;

    public static final BundlerParamInfo<File> ICON_ICO =
            new StandardBundlerParam<>(
            "icon.ico",
            File.class,
            params -> {
                File f = ICON.fetchFrom(params);
                if (f != null && !f.getName().toLowerCase().endsWith(".ico")) {
                    Log.error(MessageFormat.format(
                            I18N.getString("message.icon-not-ico"), f));
                    return null;
                }
                return f;
            },
            (s, p) -> new File(s));

    public static final StandardBundlerParam<Boolean> CONSOLE_HINT =
            new WindowsBundlerParam<>(
            Arguments.CLIOptions.WIN_CONSOLE_HINT.getId(),
            Boolean.class,
            params -> false,
            // valueOf(null) is false,
            // and we actually do want null in some cases
            (s, p) -> (s == null
            || "null".equalsIgnoreCase(s)) ? true : Boolean.valueOf(s));

    public WindowsAppImageBuilder(Map<String, Object> params, Path imageOutDir)
            throws IOException {
        super(params,
                imageOutDir.resolve(APP_NAME.fetchFrom(params) + "/runtime"));

        Objects.requireNonNull(imageOutDir);

        this.root = imageOutDir.resolve(APP_NAME.fetchFrom(params));
        this.appDir = root.resolve("app");
        this.appModsDir = appDir.resolve("mods");
        this.runtimeDir = root.resolve("runtime");
        this.mdir = runtimeDir.resolve("lib");
        this.binDir = root;
        Files.createDirectories(appDir);
        Files.createDirectories(runtimeDir);
    }

    private void writeEntry(InputStream in, Path dstFile) throws IOException {
        Files.createDirectories(dstFile.getParent());
        Files.copy(in, dstFile);
    }

    private static String getLauncherName(Map<String, ? super Object> params) {
        return APP_NAME.fetchFrom(params) + ".exe";
    }

    // Returns launcher resource name for launcher we need to use.
    public static String getLauncherResourceName(
            Map<String, ? super Object> params) {
        if (CONSOLE_HINT.fetchFrom(params)) {
            return "jpackageapplauncher.exe";
        } else {
            return "jpackageapplauncherw.exe";
        }
    }

    public static String getLauncherCfgName(
            Map<String, ? super Object> params) {
        return "app/" + APP_NAME.fetchFrom(params) +".cfg";
    }

    @Override
    public Path getAppDir() {
        return appDir;
    }

    @Override
    public Path getAppModsDir() {
        return appModsDir;
    }

    @Override
    public void prepareApplicationFiles(Map<String, ? super Object> params)
            throws IOException {
        try {
            IOUtils.writableOutputDir(root);
            IOUtils.writableOutputDir(binDir);
        } catch (PackagerException pe) {
            throw new RuntimeException(pe);
        }
        AppImageFile.save(root, params);

        // create the .exe launchers
        createLauncherForEntryPoint(params, null);

        // copy the jars
        copyApplication(params);

        // create the additional launcher(s), if any
        List<Map<String, ? super Object>> entryPoints =
                StandardBundlerParam.ADD_LAUNCHERS.fetchFrom(params);
        for (Map<String, ? super Object> entryPoint : entryPoints) {
            createLauncherForEntryPoint(AddLauncherArguments.merge(params,
                    entryPoint, ICON.getID(), ICON_ICO.getID()), params);
        }
    }

    @Override
    public void prepareJreFiles(Map<String, ? super Object> params)
        throws IOException {}

    private void createLauncherForEntryPoint(Map<String, ? super Object> params,
            Map<String, ? super Object> mainParams) throws IOException {

        var iconResource = createIconResource(TEMPLATE_APP_ICON, ICON_ICO, params,
                mainParams);
        Path iconTarget = null;
        if (iconResource != null) {
            iconTarget = binDir.resolve(APP_NAME.fetchFrom(params) + ".ico");
            if (null == iconResource.saveToFile(iconTarget)) {
                iconTarget = null;
            }
        }

        writeCfgFile(params, root.resolve(
                getLauncherCfgName(params)).toFile());

        // Copy executable to bin folder
        Path executableFile = binDir.resolve(getLauncherName(params));

        try (InputStream is_launcher =
                getResourceAsStream(getLauncherResourceName(params))) {
            writeEntry(is_launcher, executableFile);
        }

        // Update branding of launcher executable
        new ExecutableRebrander().rebrandLauncher(params, iconTarget, executableFile);

        executableFile.toFile().setExecutable(true);
        executableFile.toFile().setReadOnly();
    }

    private void copyApplication(Map<String, ? super Object> params)
            throws IOException {
        List<RelativeFileSet> appResourcesList =
                APP_RESOURCES_LIST.fetchFrom(params);
        if (appResourcesList == null) {
            throw new RuntimeException("Null app resources?");
        }
        for (RelativeFileSet appResources : appResourcesList) {
            if (appResources == null) {
                throw new RuntimeException("Null app resources?");
            }
            File srcdir = appResources.getBaseDirectory();
            for (String fname : appResources.getIncludedFiles()) {
                copyEntry(appDir, srcdir, fname);
            }
        }
    }
}
