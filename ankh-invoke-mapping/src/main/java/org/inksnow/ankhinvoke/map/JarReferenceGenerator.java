package org.inksnow.ankhinvoke.map;

import org.inksnow.ankhinvoke.map.util.SafeIoUtils;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class JarReferenceGenerator {
  private static final @NotNull String PAPERWEIGHT_MAPPINGS_NAMESPACE = "paperweight-mappings-namespace";
  private static final @NotNull Logger logger = LoggerFactory.getLogger(JarReferenceGenerator.class);
  private final @NotNull BlobReferenceGenerator blobReferenceGenerator;
  private final @Nullable String paperweightMappingsNamespace;

  public JarReferenceGenerator(@NotNull BlobReferenceGenerator blobReferenceGenerator) {
    this(blobReferenceGenerator, null);
  }

  public JarReferenceGenerator(
      @NotNull BlobReferenceGenerator blobReferenceGenerator,
      @Nullable String paperweightMappingsNamespace
  ) {
    this.blobReferenceGenerator = blobReferenceGenerator;
    if (paperweightMappingsNamespace != null
        && !paperweightMappingsNamespace.equals("mojang")
        && !paperweightMappingsNamespace.equals("mojang+yarn")
        && !paperweightMappingsNamespace.equals("spigot")) {
      throw new IllegalArgumentException("unsupported Paper mappings namespace: " + paperweightMappingsNamespace);
    }
    this.paperweightMappingsNamespace = paperweightMappingsNamespace;
  }

  public void execute(List<File> inputFiles, File outputFile) throws IOException {
    Manifest manifest = null;

    // accept scan
    for (File inputFile : inputFiles) {
      try(JarInputStream jarIn = new JarInputStream(new FileInputStream(inputFile))) {
        if (manifest == null) {
          manifest = jarIn.getManifest();
        }
        JarEntry entry;
        while ((entry = jarIn.getNextJarEntry()) != null) {
          blobReferenceGenerator.acceptScan(entry.getName(), jarIn);
        }
      }
    }

    if (paperweightMappingsNamespace != null) {
      if (manifest == null) {
        manifest = new Manifest();
      }
      Attributes mainAttributes = manifest.getMainAttributes();
      if (mainAttributes.getValue(Attributes.Name.MANIFEST_VERSION) == null) {
        mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
      }
      mainAttributes.putValue(PAPERWEIGHT_MAPPINGS_NAMESPACE, paperweightMappingsNamespace);
    }

    try (FileOutputStream output = new FileOutputStream(outputFile)) {
      JarOutputStream jarOut = manifest == null
          ? new JarOutputStream(output)
          : new JarOutputStream(output, manifest);
      try {
        for (File inputFile : inputFiles) {
          try (JarInputStream jarIn = new JarInputStream(new FileInputStream(inputFile))) {
            handle(jarIn, jarOut);
          }
        }

        for (BlobReferenceGenerator.ProcessAction processAction : blobReferenceGenerator.collect()) {
          if(processAction.isKeep() && processAction.shouldRename() && processAction.shouldReplace()) {
            jarOut.putNextEntry(new JarEntry(processAction.getNewName()));
            jarOut.write(processAction.getBytes());
          } else if (processAction.isRemove() && processAction.shouldRename()){
            logger.warn("jar generator not support remove in collect scope", new UnsupportedOperationException());
          }
        }
      } finally {
        jarOut.close();
      }
    }
  }

  private void handle(@NotNull JarInputStream jarIn, @NotNull JarOutputStream jarOut) throws IOException {
    JarEntry entry;
    while ((entry = jarIn.getNextJarEntry()) != null) {
      byte[] bytes = SafeIoUtils.readAllBytes(jarIn);
      BlobReferenceGenerator.ProcessAction processAction;
      try(InputStream in = new ByteArrayInputStream(bytes)) {
        processAction = blobReferenceGenerator.acceptProcess(entry.getName(), in);
      }
      if (processAction.isRemove()) {
        logger.info("remove {}", entry.getName());
      } else if (processAction.isKeep()) {
        jarOut.putNextEntry(copyEntry(entry, processAction.shouldRename() ? processAction.getNewName() : null));
        jarOut.write(processAction.shouldReplace() ? processAction.getBytes() : bytes);
      }
    }
  }

  @Contract("null -> null; !null -> !null")
  private JarEntry copyEntry(JarEntry entry) {
    return copyEntry(entry, null);
  }

  @Contract("null, _ -> null; !null, _ -> !null")
  private JarEntry copyEntry(JarEntry entry, @Nullable String name) {
    if (entry == null) {
      return null;
    }
    JarEntry newEntry = new JarEntry(name == null ? entry.getName() : name);
    newEntry.setTime(entry.getTime());
    newEntry.setComment(entry.getComment());
    newEntry.setExtra(entry.getExtra());
    if (entry.getCreationTime() != null) {
      newEntry.setCreationTime(entry.getCreationTime());
    }
    if (entry.getLastAccessTime() != null) {
      newEntry.setLastAccessTime(entry.getLastAccessTime());
    }
    if (entry.getLastModifiedTime() != null) {
      newEntry.setLastModifiedTime(entry.getLastModifiedTime());
    }
    newEntry.setMethod(entry.getMethod());
    return newEntry;
  }
}
