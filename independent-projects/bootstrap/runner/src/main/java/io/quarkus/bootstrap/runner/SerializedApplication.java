package io.quarkus.bootstrap.runner;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Data that reads serialized Class Path info
 *
 * This format is subject to change, and gives no compatibility guarantees, it is only intended to be used
 * with the same version of Quarkus that created it.
 */
public class SerializedApplication {

    public static final String META_INF_VERSIONS = "META-INF/versions/";
    // the files immediately (i.e. not recursively) under these paths should all be indexed
    private static final List<String> FULLY_INDEXED_PATHS = List.of("", "META-INF/services");

    private static final int MAGIC = 0XF0315432;
    private static final int VERSION = 3;

    private static final ClassLoadingResource[] EMPTY_ARRAY = new ClassLoadingResource[0];
    private static final JarResource SENTINEL = new JarResource(null, Path.of("wqxehxivam"));

    private final RunnerClassLoader runnerClassLoader;
    private final String mainClass;

    public SerializedApplication(RunnerClassLoader runnerClassLoader, String mainClass) {
        this.runnerClassLoader = runnerClassLoader;
        this.mainClass = mainClass;
    }

    public RunnerClassLoader getRunnerClassLoader() {
        return runnerClassLoader;
    }

    public String getMainClass() {
        return mainClass;
    }

    public static void write(OutputStream outputStream, String mainClass, Path applicationRoot, List<Path> classPath,
            List<Path> parentFirst, List<String> nonExistentResources)
            throws IOException {
        try (DataOutputStream data = new DataOutputStream(outputStream)) {
            data.writeInt(MAGIC);
            data.writeInt(VERSION);
            data.writeUTF(mainClass);
            // numpaths
            data.writeShort(classPath.size());
            Map<String, List<Integer>> directlyIndexedResourcesToCPJarIndex = new LinkedHashMap<>();
            for (int i = 0; i < classPath.size(); i++) {
                Path jar = classPath.get(i);
                String relativePath = applicationRoot.relativize(jar).toString().replace('\\', '/');
                data.writeUTF(relativePath);
                Collection<String> resources = writeJar(data, jar);
                for (String resource : resources) {
                    directlyIndexedResourcesToCPJarIndex.computeIfAbsent(resource, s -> new ArrayList<>()).add(i);
                }
            }
            Set<String> parentFirstPackages = new HashSet<>();

            for (Path jar : parentFirst) {
                collectPackages(jar, parentFirstPackages);
            }
            data.writeShort(parentFirstPackages.size());
            for (String p : parentFirstPackages) {
                data.writeUTF(p.replace('/', '.').replace('\\', '.'));
            }
            data.writeShort(nonExistentResources.size());
            for (String nonExistentResource : nonExistentResources) {
                data.writeUTF(nonExistentResource);
            }
            data.writeShort(directlyIndexedResourcesToCPJarIndex.size());
            for (Map.Entry<String, List<Integer>> entry : directlyIndexedResourcesToCPJarIndex.entrySet()) {
                data.writeUTF(entry.getKey());
                data.writeShort(entry.getValue().size());
                for (Integer index : entry.getValue()) {
                    data.writeShort(index);
                }
            }
            data.flush();
        }
    }

    public static SerializedApplication read(InputStream inputStream, Path appRoot) throws IOException {
        try (DataInputStream in = new DataInputStream(inputStream)) {
            if (in.readInt() != MAGIC) {
                throw new RuntimeException("Wrong magic number");
            }
            if (in.readInt() != VERSION) {
                throw new RuntimeException("Wrong class path version");
            }
            String mainClass = in.readUTF();
            ResourceDirectoryTracker resourceDirectoryTracker = new ResourceDirectoryTracker();
            Set<String> parentFirstPackages = new HashSet<>();
            int numPaths = in.readUnsignedShort();
            ClassLoadingResource[] allClassLoadingResources = new ClassLoadingResource[numPaths];
            // tmp buffer for reading the directory names
            // TODO the information of the biggest dirName could be saved somewhere in the serialized form
            byte[] tmpReadNameBytes = null;
            var reusableSubView = StringView.emptySub();
            for (int pathCount = 0; pathCount < numPaths; pathCount++) {
                String path = in.readUTF();
                boolean hasManifest = in.readBoolean();
                ManifestInfo info = null;
                if (hasManifest) {
                    info = new ManifestInfo(readNullableString(in), readNullableString(in), readNullableString(in),
                            readNullableString(in), readNullableString(in), readNullableString(in));
                }
                JarResource resource = new JarResource(info, appRoot.resolve(path));
                allClassLoadingResources[pathCount] = resource;
                int numDirs = in.readUnsignedShort();
                for (int i = 0; i < numDirs; ++i) {
                    int encodedLength = in.readShort();
                    // negative length indicates that the string is UTF-8 encoded
                    final boolean isUTF8 = encodedLength < 0;
                    if (isUTF8) {
                        encodedLength = -encodedLength;
                    }
                    // enlarge the scratch buffer if needed
                    if (tmpReadNameBytes == null || tmpReadNameBytes.length < encodedLength) {
                        // let's be generous and double the size, so save further resizes
                        tmpReadNameBytes = new byte[encodedLength * 2];
                    }
                    int readBytes = in.read(tmpReadNameBytes, 0, encodedLength);
                    assert readBytes == encodedLength;
                    // TODO not sure if having separate call-sites is better or not: it means compiling 2 methods instead of one!
                    String fullDirName = isUTF8 ? new String(tmpReadNameBytes, 0, encodedLength, StandardCharsets.UTF_8)
                            // Save String to verify if the byte[] content really contains negatives!
                            : new String(tmpReadNameBytes, 0, 0, encodedLength);
                    var dirName = StringView.of(fullDirName);
                    // now try to be smart and save some memory by NOT having substrings over and over again
                    final int subDirs = in.readInt();
                    for (int j = 0; j < subDirs; j++) {
                        // try reuse the same StringView instance: if the tracker already contains it, which is
                        // very common, we can avoid creating a new instance
                        reusableSubView.with(fullDirName, in.readInt(), in.readUnsignedShort());
                        if (resourceDirectoryTracker.addResourceDir(reusableSubView, resource)) {
                            // we need to create a new StringView instance, as the ownership has been taken over
                            reusableSubView = StringView.emptySub();
                        } else {
                            reusableSubView.reset();
                        }
                    }
                    resourceDirectoryTracker.addResourceDir(dirName, resource);
                }
            }
            int packages = in.readUnsignedShort();
            for (int i = 0; i < packages; ++i) {
                parentFirstPackages.add(in.readUTF());
            }
            Set<String> nonExistentResources = new HashSet<>();
            int nonExistentResourcesSize = in.readUnsignedShort();
            for (int i = 0; i < nonExistentResourcesSize; i++) {
                nonExistentResources.add(in.readUTF());
            }
            // this map is populated correctly because the JarResource entries are added to allClassLoadingResources
            // in the same order as the classpath was written during the writing of the index
            int directlyIndexedSize = in.readUnsignedShort();
            Map<String, ClassLoadingResource[]> directlyIndexedResourcesIndexMap = new HashMap<>(directlyIndexedSize);
            for (int i = 0; i < directlyIndexedSize; i++) {
                String resource = in.readUTF();
                int indexesSize = in.readUnsignedShort();
                ClassLoadingResource[] matchingResources = new ClassLoadingResource[indexesSize];
                for (int j = 0; j < indexesSize; j++) {
                    matchingResources[j] = allClassLoadingResources[in.readUnsignedShort()];
                }
                directlyIndexedResourcesIndexMap.put(resource, matchingResources);
            }
            RunnerClassLoader runnerClassLoader = new RunnerClassLoader(ClassLoader.getSystemClassLoader(),
                    resourceDirectoryTracker.getResult(), parentFirstPackages,
                    nonExistentResources, FULLY_INDEXED_PATHS, directlyIndexedResourcesIndexMap);
            for (ClassLoadingResource classLoadingResource : allClassLoadingResources) {
                classLoadingResource.init();
            }
            return new SerializedApplication(runnerClassLoader, mainClass);
        }
    }

    private static String readNullableString(DataInputStream in) throws IOException {
        if (in.readBoolean()) {
            return in.readUTF();
        }
        return null;
    }

    /**
     * @return a List of all resources that exist in the paths that we desire to have fully indexed
     *         (configured via {@code FULLY_INDEXED_PATHS})
     */
    private static List<String> writeJar(DataOutputStream out, Path jar) throws IOException {
        try (JarFile zip = new JarFile(jar.toFile())) {
            Manifest manifest = zip.getManifest();
            if (manifest == null) {
                out.writeBoolean(false);
            } else {
                //write the manifest
                Attributes ma = manifest.getMainAttributes();
                if (ma == null) {
                    out.writeBoolean(false);
                } else {
                    out.writeBoolean(true);
                    writeNullableString(out, ma.getValue(Attributes.Name.SPECIFICATION_TITLE));
                    writeNullableString(out, ma.getValue(Attributes.Name.SPECIFICATION_VERSION));
                    writeNullableString(out, ma.getValue(Attributes.Name.SPECIFICATION_VENDOR));
                    writeNullableString(out, ma.getValue(Attributes.Name.IMPLEMENTATION_TITLE));
                    writeNullableString(out, ma.getValue(Attributes.Name.IMPLEMENTATION_VERSION));
                    writeNullableString(out, ma.getValue(Attributes.Name.IMPLEMENTATION_VENDOR));
                }
            }

            Set<String> dirs = new LinkedHashSet<>();
            Map<String, List<String>> fullyIndexedPaths = new LinkedHashMap<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            boolean hasDefaultPackage = false;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().contains("/")) {
                    hasDefaultPackage = true;
                    if (!entry.getName().isEmpty() && FULLY_INDEXED_PATHS.contains("")) {
                        fullyIndexedPaths.computeIfAbsent("", SerializedApplication::newFullyIndexedPathsValue)
                                .add(entry.getName());
                    }
                } else if (!entry.isDirectory()) {
                    //some jars don't have correct directory entries
                    //so we look at the file paths instead
                    //looking at you h2
                    final int index = entry.getName().lastIndexOf('/');
                    dirs.add(entry.getName().substring(0, index));

                    if (entry.getName().startsWith(META_INF_VERSIONS)) {
                        //multi release jar
                        //we add all packages here
                        //they may not be relevant for some versions, but that is fine
                        String part = entry.getName().substring(META_INF_VERSIONS.length());
                        int slash = part.indexOf("/");
                        if (slash != -1) {
                            final int subIndex = part.lastIndexOf('/');
                            if (subIndex != slash) {
                                dirs.add(part.substring(slash + 1, subIndex));
                            }
                        }
                    }

                    for (int i = 0; i < FULLY_INDEXED_PATHS.size(); i++) {
                        String path = FULLY_INDEXED_PATHS.get(i);
                        if (path.isEmpty()) {
                            continue;
                        }
                        if (entry.getName().startsWith(path)) {
                            fullyIndexedPaths.computeIfAbsent(path, SerializedApplication::newFullyIndexedPathsValue)
                                    .add(entry.getName());
                        }
                    }
                }
            }
            if (hasDefaultPackage) {
                dirs.add("");
            }
            out.writeShort(dirs.size());
            for (String dirName : dirs) {
                // push this a bit forward to help the read to get faster!
                // write the positions of each / in the string
                var subDirs = new ArrayList<String>();
                int subDirLength = dirName.indexOf('/');
                while (subDirLength >= 0) {
                    var subDirName = dirName.substring(0, subDirLength);
                    subDirName.hashCode();
                    subDirs.add(subDirName);
                    subDirLength = dirName.indexOf('/', subDirLength + 1);
                }
                // write in the opposite order here, to hydrate StringView(s) in the right order
                var dirNameUtf8 = dirName.getBytes(StandardCharsets.UTF_8);
                // using a negative length to indicate that the string is UTF-8 encoded, which should be less frequent
                int writtenLength = dirNameUtf8.length == dirName.length() ? dirName.length() : -dirNameUtf8.length;
                out.writeShort(writtenLength);
                out.write(dirNameUtf8);
                // TODO these could be made cheaper; maybe a short?
                out.writeInt(subDirs.size());
                for (String subDir : subDirs) {
                    out.writeInt(subDir.hashCode());
                    out.writeShort(subDir.length());
                }
            }
            List<String> result = new ArrayList<>();
            for (List<String> values : fullyIndexedPaths.values()) {
                result.addAll(values);
            }
            return result;
        }
    }

    private static List<String> newFullyIndexedPathsValue(String ignored) {
        return new ArrayList<>(10);
    }

    private static void collectPackages(Path jar, Set<String> dirs) throws IOException {
        if (Files.isDirectory(jar)) {
            //this can only really happen when testing quarkus itself
            //but is included for completeness
            Files.walkFileTree(jar, new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    dirs.add(jar.relativize(dir).toString());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            try (JarFile zip = new JarFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory()) {
                        //some jars don't have correct  directory entries
                        //so we look at the file paths instead
                        //looking at you h2
                        final int index = entry.getName().lastIndexOf('/');
                        if (index > 0) {
                            dirs.add(entry.getName().substring(0, index));
                        }
                    }
                }
            }
        }
    }

    private static void writeNullableString(DataOutputStream out, String string) throws IOException {
        if (string == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(string);
        }
    }

    /**
     * This class is to build up the final directory to Resource map
     * The idea here is that directories will only be contained by a single resource, so we optimistically
     * create a single item array when we encounter a directory for the first time.
     * However, when a resource is added for the same directory, we switch to using a Set to track the resources
     * of that directory and then at the end use those sets to override to create the final array of resources.
     * The reason for doing it this way to only create Sets when needed (which is only a fraction of the cases)
     */
    private static class ResourceDirectoryTracker {
        private final Map<StringView, ClassLoadingResource[]> result = new HashMap<>();
        private final Map<StringView, Set<ClassLoadingResource>> overrides = new HashMap<>();

        /**
         * It returns true if the StringView ownership has been taken over by the tracker
         */
        boolean addResourceDir(StringView dir, JarResource resource) {
            ClassLoadingResource[] existing = result.get(dir);
            if (existing == null) {
                // this is the first the dir was ever tracked
                result.put(dir, new JarResource[] { resource });
                return true;
            } else {
                ClassLoadingResource existingResource = existing[0];
                // we don't need to do anything as the resource has already been tracked and an attempt
                // to add it again was made
                if (!existingResource.equals(resource)) {
                    Set<ClassLoadingResource> dirOverrides = overrides.get(dir);
                    if (dirOverrides == null) {
                        // we need to create the override set as this is the first time we find a resource for the dir
                        // that is not the same as the one in the array
                        dirOverrides = new LinkedHashSet<>(2);
                        dirOverrides.add(existingResource);
                        dirOverrides.add(resource);
                        overrides.put(dir, dirOverrides);
                        //replace the value in the original array with a sentinel in order to allow for quick comparisons
                        existing[0] = SENTINEL;
                        return true;
                    } else {
                        // in this case, overrides has already been created in a previous invocation so all we need to
                        // do is add the new resource
                        dirOverrides.add(resource);
                        return false;
                    }
                }
                return false;
            }
        }

        Map<StringView, ClassLoadingResource[]> getResult() {
            overrides.forEach(this::addToResult);
            return result;
        }

        private void addToResult(StringView dir, Set<? extends ClassLoadingResource> jarResources) {
            result.put(dir, jarResources.toArray(EMPTY_ARRAY));
        }
    }

}
