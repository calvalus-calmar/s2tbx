package org.esa.snap.utils;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.esa.snap.core.util.StringUtils;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Helper methods for native libraries registration.
 *
 * @author  Cosmin Cara
 * @since   5.0.0
 */
public class NativeLibraryUtils {
    private static final String ENV_LIB_PATH = "java.library.path";

    public static void registerNativePath(String path) {
        String propertyValue = System.getProperty(ENV_LIB_PATH);
        if (!StringUtils.isNullOrEmpty(propertyValue)) {
            propertyValue += File.pathSeparator + path;
        } else {
            propertyValue = path;
        }
        System.setProperty(ENV_LIB_PATH, propertyValue);
        try {
            PrivilegedAccessor.setStaticValue(ClassLoader.class, "sys_paths", null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads library either from a JAR archive, or from file system
     * The file from JAR is copied into system temporary directory and then loaded.
     *
     * @param path          The path from which the load is attempted
     * @param libraryName   The name of the library to be loaded (without extension)
     * @throws IOException              If temporary file creation or read/write operation fails
     */
    public static void loadLibrary(String path, String libraryName) throws IOException {
        path = URLDecoder.decode(path, "UTF-8");
        String mappedLibName = System.mapLibraryName(libraryName);
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        path = path.replace('/', File.separatorChar);
        if (path.contains(".jar")) {
            int contentsSeparatorIndex = path.indexOf("!");
            String jarPath = path.substring(0, contentsSeparatorIndex);
            JarFile jarFile = new JarFile(jarPath);
            Enumeration<JarEntry> entries = jarFile.entries();
            JarEntry jarEntry = null;
            while (entries.hasMoreElements()) {
                JarEntry currentEntry = entries.nextElement();
                if (StringHelper.containsIgnoreCase(currentEntry.getName(), libraryName)) {
                    jarEntry = currentEntry;
                    break;
                }
            }
            if (jarEntry == null) {
                throw new IOException(String.format("Library %s could not be found in the jar file %s", libraryName, path));
            }
            try (InputStream in = jarFile.getInputStream(jarEntry)) {
                Path tmpPath = Paths.get(System.getProperty("java.io.tmpdir"), "lib", getOSFamily(), mappedLibName);
                try (OutputStream out = FileUtils.openOutputStream(tmpPath.toFile())) {
                    IOUtils.copy(in, out);
                }
                path = tmpPath.toAbsolutePath().toString();
            }
        } else {
            if (!Files.exists(Paths.get(path, mappedLibName))) {
                throw new IOException(String.format("Library %s could not be found in %s", mappedLibName, path));
            }
        }
        registerNativePath(path);
        //System.load(Paths.get(path, mappedLibName).toAbsolutePath().toString());
        System.loadLibrary(libraryName);
    }

    public static String getOSFamily() {
        String ret;
        String sysName = System.getProperty("os.name").toLowerCase();
        String sysArch = System.getProperty("os.arch").toLowerCase();
        if (sysName.contains("windows")) {
            if (sysArch.contains("amd64") || sysArch.contains("x86_x64")) {
                ret = "win64";
            } else {
                ret = "win32";
            }
        } else if (sysName.contains("linux")) {
            ret = "linux";
        } else if (sysName.contains("mac")) {
            ret = "macosx";
        } else {
            throw new NotImplementedException();
        }
        return ret;
    }
}