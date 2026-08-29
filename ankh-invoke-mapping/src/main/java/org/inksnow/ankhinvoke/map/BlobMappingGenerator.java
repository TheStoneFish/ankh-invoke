package org.inksnow.ankhinvoke.map;

import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.provider.JarProvider;
import net.md_5.specialsource.provider.JointProvider;
import org.inksnow.ankhinvoke.codec.BlobMap;
import org.inksnow.ankhinvoke.codec.util.NoCloseInputStream;
import org.inksnow.ankhinvoke.map.bean.ClassBean;
import org.inksnow.ankhinvoke.map.bean.FieldBean;
import org.inksnow.ankhinvoke.map.bean.MethodBean;
import org.inksnow.ankhinvoke.map.map.ProguardMap;
import org.inksnow.ankhinvoke.map.util.AiStringUtils;
import org.inksnow.ankhinvoke.map.util.LineBufferPrintStream;
import org.inksnow.ankhinvoke.map.util.SafeIoUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.GZIPOutputStream;

public final class BlobMappingGenerator {
  private static final @NotNull String SPIGOT_BUILD_DATA_URL = "https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/raw/";
  private static final int DOWNLOAD_ATTEMPTS = 3;
  private static final int DOWNLOAD_CONNECT_TIMEOUT_MILLIS = 15_000;
  private static final int DOWNLOAD_READ_TIMEOUT_MILLIS = 120_000;
  private static final long DOWNLOAD_RETRY_DELAY_MILLIS = 1_000L;
  private static final @NotNull Gson gson = new GsonBuilder().create();

  private final @NotNull Consumer<String> logFunction;
  private final @NotNull File cacheDirectory;
  private final boolean disableCache;
  private final @NotNull File targetFile;
  private final @NotNull String minecraftVersion;
  private final @Nullable String buildDataHash;
  private final @Nullable File buildDataDirectory;
  private final boolean useSpigotMapping;

  private final @NotNull JointProvider inheritanceProviders;
  private final @NotNull JarMapping spigotJarMapping;
  private final @NotNull JarMapping mojangJarMapping;
  private final @NotNull Remapper spigotRemapper;
  private final @NotNull Remapper mojangRemapper;
  private final @NotNull Map<@NotNull String, @NotNull ClassBean> classBeanMap;

  private BlobMappingGenerator(@NotNull Consumer<String> logFunction, @NotNull File cacheDirectory, boolean disableCache, @NotNull File targetFile, @NotNull String minecraftVersion, @Nullable String buildDataHash, @Nullable File buildDataDirectory, boolean useSpigotMapping) {
    this.logFunction = logFunction;
    this.cacheDirectory = cacheDirectory;
    this.disableCache = disableCache;
    this.targetFile = targetFile;
    this.minecraftVersion = minecraftVersion;
    this.buildDataHash = buildDataHash;
    this.buildDataDirectory = buildDataDirectory;
    this.useSpigotMapping = useSpigotMapping;

    this.inheritanceProviders = new JointProvider();
    this.spigotJarMapping = new JarMapping();
    this.spigotJarMapping.setFallbackInheritanceProvider(this.inheritanceProviders);
    this.mojangJarMapping = new JarMapping();
    this.mojangJarMapping.setFallbackInheritanceProvider(this.inheritanceProviders);
    this.spigotRemapper = new ChainRemapper(new JarRemapper(spigotJarMapping), new JarRemapper(mojangJarMapping));
    this.mojangRemapper = new JarRemapper(mojangJarMapping);
    this.classBeanMap = new HashMap<>();
  }

  public static void main(String[] args) throws IOException {
    BlobMappingGenerator generator = builder()
        .setCacheDirectory(new File("/home/inkerbot/IdeaProjects/ankh-invoke/ankh-invoke-nbt/target/maven-status/ankh-invoke-cache"))
        .setTargetFile(new File("/home/inkerbot/IdeaProjects/ankh-invoke/ankh-invoke-nbt/target/classes/org/inksnow/ankhinvoke/ankh-invoke-nbt/mappings/mojang-1.20.2"))
        .setUseSpigotMapping(false)
        .setLogFunction(System.out::println)
        .setMinecraftVersion("1.20.2")
        .setBuildDataHash("172197ceb99364701937947ea7fc424ecf1bb694")
        .build();
    generator.run();

    File dumpTxtFile = new File("/home/inkerbot/IdeaProjects/ankh-invoke/ankh-invoke-nbt/target/classes/org/inksnow/ankhinvoke/ankh-invoke-nbt/mappings/mojang-1.20.2.txt");
    ProguardMap.load(generator.createBlobMap());
    ProguardMap proguardMap = ProguardMap.load(generator.createBlobMap());
    Files.createParentDirs(dumpTxtFile);
    try (Writer writer = Files.newWriter(dumpTxtFile, StandardCharsets.UTF_8)) {
      proguardMap.save(writer);
    }
  }

  private void saveBlobMap(File cacheTmpFile, File cacheFile) throws IOException {
    BlobMap blobMap = createBlobMap();
    Files.createParentDirs(cacheTmpFile);
    try(DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(cacheTmpFile)))) {
      blobMap.save(out);
    }
    Files.createParentDirs(cacheFile);
    if (cacheFile.exists()) {
      if (!cacheFile.delete()) {
        throw new IOException("Failed to delete " + cacheFile);
      }
    }
    if (!cacheTmpFile.renameTo(cacheFile)) {
      throw new IOException("[saveBlobMap] Failed to rename " + cacheTmpFile + " to " + cacheFile);
    }
    Files.createParentDirs(targetFile);
    Files.copy(cacheFile, targetFile);
  }

  private @NotNull BlobMap createBlobMap() {
    BlobMap.Builder builder = BlobMap.builder();
    for (Map.Entry<String, ClassBean> classEntry : classBeanMap.entrySet()) {
      builder.appendClass(classEntry.getKey(), classEntry.getValue().remapped());
      for (Map.Entry<FieldBean, String> fieldEntry : classEntry.getValue().fieldMap().entrySet()) {
        builder.appendField(classEntry.getKey(), fieldEntry.getKey().name(), fieldEntry.getKey().desc(), fieldEntry.getValue());
      }
      for (Map.Entry<MethodBean, String> methodEntry : classEntry.getValue().methodMap().entrySet()) {
        builder.appendMethod(classEntry.getKey(), methodEntry.getKey().name(), methodEntry.getKey().desc(), methodEntry.getValue());
      }
    }
    return builder.build();
  }

  private void loadMappings(JsonObject buildDataInfo) throws IOException {
    String serverUrl = buildDataInfo.get("serverUrl").getAsString();
    String mappingsUrl = buildDataInfo.get("mappingsUrl").getAsString();

    inheritanceProviders.add(new JarProvider(Jar.init(getRemoteFile(serverUrl))));

    try (BufferedReader reader = getRemoteReader(mappingsUrl)) {
      mojangJarMapping.loadMappings(reader, null, null, false);
    }

    JsonElement classMappingsElement = buildDataInfo.get("classMappings");
    if (classMappingsElement != null) {
      try (BufferedReader reader = getBuildDataReader("mappings/" + classMappingsElement.getAsString())) {
        spigotJarMapping.loadMappings(reader, null, null, false);
      }
    }

    JsonElement memberMappingsElement = buildDataInfo.get("memberMappings");
    if (memberMappingsElement != null) {
      try (BufferedReader reader = getBuildDataReader("mappings/" + memberMappingsElement.getAsString())) {
        spigotJarMapping.loadMappings(reader, null, null, false);
      }
    }

    JsonElement packageMappingsElement = buildDataInfo.get("packageMappings");
    if (packageMappingsElement != null) {
      try (BufferedReader reader = getBuildDataReader("mappings/" + packageMappingsElement.getAsString())) {
        spigotJarMapping.loadMappings(reader, null, null, false);
      }
    }
  }

  private void scanJar(@NotNull JarInputStream in) throws IOException {
    JarEntry entry;
    while ((entry = in.getNextJarEntry()) != null) {
      if (entry.isDirectory()) {
        continue;
      }
      if (entry.getName().endsWith(".jar")) {
        log("process nested jar: ", entry.getName());
        try (JarInputStream jarIn = new JarInputStream(new NoCloseInputStream(in))) {
          scanJar(jarIn);
        }
        continue;
      }
      if (entry.getName().endsWith(".class")) {
        try(InputStream classIn = new NoCloseInputStream(in)) {
          scanClass(classIn);
        }catch (Exception e) {
          log("Failed to scan class, skipped: ", entry.getName(), e);
        }
      }
    }
  }

  public void run() throws IOException {
    log("generate reobf mappings");
    String buildDataIdentity = buildDataHash == null ? "local" : buildDataHash;
    String fileName = minecraftVersion + "_" + buildDataIdentity + "_" + (useSpigotMapping ? "spigot" : "mojang");
    File cacheFile = getCacheFile("blobmap", fileName, false);
    File cacheTmpFile = getCacheFile("blobmap", fileName, true);
    if (false && !disableCache) {
      if(targetFile.exists()) {
        return;
      } else if (cacheFile.exists()) {
        Files.createParentDirs(targetFile);
        Files.copy(cacheFile, targetFile);
        return;
      }
    }

    JsonObject buildDataInfo;
    try (BufferedReader reader = getBuildDataReader("info.json")) {
      buildDataInfo = gson.fromJson(reader, JsonObject.class);
    }
    String minecraftVersion = buildDataInfo.get("minecraftVersion").getAsString();
    if (!this.minecraftVersion.startsWith(minecraftVersion)) {
      throw new IllegalStateException("Version mismatch: " + this.minecraftVersion + " != " + minecraftVersion);
    }

    String serverUrl = buildDataInfo.get("serverUrl").getAsString();

    loadMappings(buildDataInfo);

    try (JarInputStream in = new JarInputStream(new FileInputStream(getRemoteFile(serverUrl)))) {
      scanJar(in);
    }
    saveBlobMap(cacheTmpFile, cacheFile);
    Files.copy(cacheFile, targetFile);
    log("generate success, saved to ", targetFile);
  }

  private @NotNull File getFile(@Nullable String hash, @NotNull String path) throws IOException {
    URL url;
    File cacheFile, cacheTmpFile;
    if (hash == null) {
      url = new URL(path);
      String fileName = AiStringUtils.substringAfterLastOrdinal(path, "/", 2);
      cacheFile = getCacheFile("mojang", fileName, false);
      cacheTmpFile = getCacheFile("mojang", fileName, true);
    } else {
      url = new URL(SPIGOT_BUILD_DATA_URL + path + "?at=" + hash);
      String fileName = path;
      cacheFile = getCacheFile("spigot:" + hash, fileName, false);
      cacheTmpFile = getCacheFile("spigot:" + hash, fileName, true);
    }

    if (cacheFile.exists() && !disableCache) {
      return cacheFile;
    }
    log("downloading: ", url, " to ", cacheFile);
    download(url, cacheTmpFile);

    Files.createParentDirs(cacheFile);
    if (!cacheTmpFile.renameTo(cacheFile)) {
      throw new IOException("[getFile] Failed to rename " + cacheTmpFile + " to " + cacheFile);
    }
    return cacheFile;
  }

  private void download(@NotNull URL url, @NotNull File cacheTmpFile) throws IOException {
    IOException lastException = null;
    for (URL downloadUrl : getDownloadUrls(url)) {
      for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
        HttpURLConnection httpConnection = null;
        try {
          URLConnection connection = downloadUrl.openConnection();
          connection.setConnectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MILLIS);
          connection.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MILLIS);
          if (connection instanceof HttpURLConnection) {
            httpConnection = (HttpURLConnection) connection;
            httpConnection.setInstanceFollowRedirects(true);
            httpConnection.setRequestProperty("User-Agent", "ankh-invoke");
            int responseCode = httpConnection.getResponseCode();
            if (responseCode >= 300 && responseCode < 500
                && responseCode != 408 && responseCode != 429) {
              throw new NonRetryableDownloadException(
                  "Unexpected response code: " + responseCode + " " + httpConnection.getResponseMessage()
              );
            }
            if (responseCode >= 500) {
              throw new IOException(
                  "Unexpected response code: " + responseCode + " " + httpConnection.getResponseMessage()
              );
            }
          }

          Files.createParentDirs(cacheTmpFile);
          try (InputStream in = connection.getInputStream();
               OutputStream out = new FileOutputStream(cacheTmpFile, false)) {
            ByteStreams.copy(in, out);
          }
          return;
        } catch (NonRetryableDownloadException e) {
          throw e;
        } catch (IOException e) {
          lastException = e;
          if (attempt == DOWNLOAD_ATTEMPTS) {
            break;
          }
          log("download failed, retrying (", attempt, "/", DOWNLOAD_ATTEMPTS, "): ", downloadUrl, " - ", e.toString());
          try {
            Thread.sleep(DOWNLOAD_RETRY_DELAY_MILLIS * attempt);
          } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retrying download: " + downloadUrl, interruptedException);
          }
        } finally {
          if (httpConnection != null) {
            httpConnection.disconnect();
          }
        }
      }
      log("download source failed, trying fallback: ", downloadUrl);
    }
    if (lastException == null) {
      throw new IOException("No download source available: " + url);
    }
    throw lastException;
  }

  private @NotNull URL[] getDownloadUrls(@NotNull URL url) throws IOException {
    if ("piston-data.mojang.com".equalsIgnoreCase(url.getHost())) {
      return new URL[]{
          url,
          new URL(url.getProtocol(), "launcher.mojang.com", url.getPort(), url.getFile())
      };
    }
    if ("launcher.mojang.com".equalsIgnoreCase(url.getHost())) {
      return new URL[]{
          url,
          new URL(url.getProtocol(), "piston-data.mojang.com", url.getPort(), url.getFile())
      };
    }
    return new URL[]{url};
  }

  private static final class NonRetryableDownloadException extends IOException {
    private NonRetryableDownloadException(@NotNull String message) {
      super(message);
    }
  }

  private @NotNull File getRemoteFile(@NotNull String path) throws IOException {
    return getFile(null, path);
  }

  private @NotNull File getBuildDataFile(@NotNull String path) throws IOException {
    if (buildDataDirectory == null) {
      if (buildDataHash == null) {
        throw new IllegalStateException("buildDataHash is null");
      }
      return getFile(buildDataHash, path);
    }

    File file = new File(buildDataDirectory, path);
    if (!file.isFile()) {
      throw new FileNotFoundException("BuildData file not found: " + file);
    }
    return file;
  }

  private @NotNull File getCacheFile(@NotNull String cacheKey, @NotNull String name, boolean isTmp) {
    cacheKey = SafeIoUtils.safeFileName(cacheKey.replace('-', '_'));
    name = SafeIoUtils.safeFileName(name);
    return new File(cacheDirectory, cacheKey + "-" + name + (isTmp ? ".tmp" : ""));
  }

  private @NotNull BufferedReader getRemoteReader(@NotNull String path) throws IOException {
    return new BufferedReader(new InputStreamReader(new FileInputStream(getRemoteFile(path)), StandardCharsets.UTF_8));
  }

  private @NotNull BufferedReader getBuildDataReader(@NotNull String path) throws IOException {
    return new BufferedReader(new InputStreamReader(new FileInputStream(getBuildDataFile(path)), StandardCharsets.UTF_8));
  }

  private void scanClass(@NotNull InputStream in) throws IOException {
    ClassNode classNode = new ClassNode();
    new ClassReader(in)
        .accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    Remapper remapper = useSpigotMapping ? spigotRemapper : mojangRemapper;
    String mappedClassName;
    String spigotClassName;
    if (classNode.nestHostClass != null && classNode.name.startsWith(classNode.nestHostClass + "$")) {
      String innerName = classNode.name.substring(classNode.nestHostClass.length() + 1);
      String mappedHostClassName = remapper.map(classNode.nestHostClass);
      String spigotHostClassName = spigotRemapper.map(classNode.nestHostClass);

      String mappedInnerClassName = remapper.mapInnerClassName(classNode.name, classNode.nestHostClass, innerName);
      String spigotInnerClassName = spigotRemapper.mapInnerClassName(classNode.name, classNode.nestHostClass, innerName);

      mappedClassName = mappedHostClassName + "$" + (mappedInnerClassName.isEmpty() ? innerName : mappedInnerClassName);
      spigotClassName = spigotHostClassName + "$" + (spigotInnerClassName.isEmpty() ? innerName : spigotInnerClassName);

      if (AnkhInvokeMapping.DEBUG) {
        log("inner class found: ", classNode.name, " with host ", classNode.nestHostClass);
      }
    } else {
      mappedClassName = remapper.map(classNode.name);
      spigotClassName = spigotRemapper.map(classNode.name);
    }
    ClassBean classBean = classBeanMap.getOrDefault(mappedClassName, new ClassBean(mappedClassName, spigotClassName));

    if (AnkhInvokeMapping.DEBUG) {
      log("mapped class name: ", mappedClassName, " -> ", spigotClassName);
    }
    for (FieldNode field : classNode.fields) {
      String mappedFieldName = remapper.mapFieldName(classNode.name, field.name, field.desc);

      if (!mappedFieldName.equals(field.name)) {
        String mappedFieldDesc = remapper.mapDesc(field.desc);
        classBean.fieldMap().put(new FieldBean(mappedClassName, mappedFieldName, mappedFieldDesc), field.name);
      }
    }

    for (MethodNode methodNode : classNode.methods) {
      String mappedMethodName = remapper.mapMethodName(classNode.name, methodNode.name, methodNode.desc);

      if (!mappedMethodName.equals(methodNode.name)) {
        String mappedMethodDesc = mojangRemapper.mapDesc(methodNode.desc);
        classBean.methodMap().put(new MethodBean(mappedClassName, mappedMethodName, mappedMethodDesc), methodNode.name);
      }
    }

    if (!classBean.raw().equals(classBean.remapped()) || !classBean.fieldMap().isEmpty() || !classBean.methodMap().isEmpty()) {
      classBeanMap.put(mappedClassName, classBean);
    }
  }

  public static @NotNull Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private @NotNull Consumer<@NotNull String> logFunction = System.out::println;
    private @Nullable File cacheDirectory;
    private @Nullable File targetFile;
    private @Nullable String minecraftVersion;
    private @Nullable String buildDataHash;
    private @Nullable File buildDataDirectory;
    private boolean useSpigotMapping;
    private boolean disableCache;

    public @NotNull Builder setLogFunction(Consumer<String> logFunction) {
      this.logFunction = logFunction;
      return this;
    }

    public @NotNull Builder setCacheDirectory(@NotNull File cacheDirectory) {
      this.cacheDirectory = cacheDirectory;
      return this;
    }

    public @NotNull Builder setTargetFile(@NotNull File targetFile) {
      this.targetFile = targetFile;
      return this;
    }

    public @NotNull Builder setMinecraftVersion(@NotNull String minecraftVersion) {
      this.minecraftVersion = minecraftVersion;
      return this;
    }

    public @NotNull Builder setBuildDataHash(@Nullable String buildDataHash) {
      this.buildDataHash = buildDataHash;
      return this;
    }

    public @NotNull Builder setBuildDataDirectory(@Nullable File buildDataDirectory) {
      this.buildDataDirectory = buildDataDirectory;
      return this;
    }

    public @NotNull Builder setUseSpigotMapping() {
      this.useSpigotMapping = true;
      return this;
    }

    public @NotNull Builder setUseSpigotMapping(boolean useSpigotMapping) {
      this.useSpigotMapping = useSpigotMapping;
      return this;
    }

    public @NotNull Builder setDisableCache() {
      this.disableCache = true;
      return this;
    }

    public @NotNull Builder setDisableCache(boolean disableCache) {
      this.disableCache = disableCache;
      return this;
    }

    public @NotNull BlobMappingGenerator build() {
      if (cacheDirectory == null) {
        throw new IllegalStateException("cacheDirectory is null");
      }
      if (targetFile == null) {
        throw new IllegalStateException("targetFile is null");
      }
      if (minecraftVersion == null) {
        throw new IllegalStateException("minecraftVersion is null");
      }
      if (buildDataDirectory != null && buildDataHash != null) {
        throw new IllegalArgumentException("buildDataDirectory and buildDataHash cannot both be configured");
      }
      if (buildDataDirectory == null && buildDataHash == null) {
        buildDataHash = SpigotBuildData.requireVersion(minecraftVersion);
      }
      if (buildDataDirectory != null && !buildDataDirectory.isDirectory()) {
        throw new IllegalArgumentException("buildDataDirectory is not a directory: " + buildDataDirectory);
      }
      return new BlobMappingGenerator(logFunction, cacheDirectory, disableCache, targetFile, minecraftVersion, buildDataHash, buildDataDirectory, useSpigotMapping);
    }
  }

  private void log(@NotNull Object @NotNull ... messages) {
    StringBuilder builder = new StringBuilder();
    Throwable e = null;
    for (int i = 0; i < messages.length; i++) {
      Object message = messages[i];
      if (i == messages.length - 1 && message instanceof Throwable) {
        e = (Throwable) message;
      } else {
        builder.append(message);
      }
    }
    logFunction.accept(builder.toString());
    if (e != null) {
      try (PrintStream printStream = new LineBufferPrintStream(logFunction)) {
        e.printStackTrace(printStream);
      }
    }
  }
}
