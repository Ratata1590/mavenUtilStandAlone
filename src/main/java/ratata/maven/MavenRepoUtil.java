package ratata.maven;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.installation.InstallRequest;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.repository.AuthenticationBuilder;

import com.fasterxml.jackson.databind.JsonNode;

public class MavenRepoUtil {
  public static final String ratataRepo = "/ratataRepo";

  public static final String keyName = "name";
  public static final String keyParent = "parent";
  public static final String parent_SEP = "/";
  public static final String keyData = "data";

  public static final String keyId = "id";
  public static final String keyType = "type";
  public static final String keyUrl = "url";
  public static final String keyProxyURL = "proxyUrl";
  public static final String keyProxyProtocol = "protocol";
  public static final String keyProxyPort = "proxyPort";
  public static final String keyUsername = "username";
  public static final String keyPassword = "password";
  public static final String keyDependencies = "dependencies";

  public static final String keyGroupId = "groupId";
  public static final String keyArtifactId = "artifactId";
  public static final String keyVersion = "version";

  public static final String defaultSystemName = "system";

  public static final String defaultId = "central";
  public static final String defaultIdLocal = "local";
  public static final String defaultType = "default";
  public static final String defaultUrl = "http://repo1.maven.org/maven2/";
  public static final String defaultProxyProtocol = "http";
  public static final String defaultMAVEN_META_FILENAME = "maven-metadata-local.xml";

  private static RepositorySystem repositorySystem;
  private static LocalRepository localRepo;
  private static DefaultRepositorySystemSession systemSession;

  public static void uploadJarToRepo(File tempFile, String groupId, String artifactId,
      String version) throws Exception {
    FileUtils.deleteDirectory(
        new File(localRepo.getBasedir().getPath().concat(File.separator).concat(groupId)
            .concat(File.separator).concat(artifactId).concat(File.separator).concat(version)));
    Artifact jarArtifact =
        new DefaultArtifact(groupId, artifactId, null, "jar", version, null, tempFile);
    InstallRequest install = new InstallRequest();
    install.addArtifact(jarArtifact);
    repositorySystem.install(systemSession, install);
    tempFile.delete();
  }

  public static String getMetadata(String groupId, String artifactId) throws Exception {
    File metaFile = new File(localRepo.getBasedir().getPath().concat(File.separator).concat(groupId)
        .concat(File.separator).concat(artifactId).concat(File.separator)
        .concat(defaultMAVEN_META_FILENAME));
    return new String(Files.readAllBytes(Paths.get(metaFile.getPath())));
  }

  public static void createClassLoader(String classLoaderName, JsonNode mavenDependencies,
      Map<String, JsonNode> configList, Map<String, ClassLoader> classLoaderList) throws Exception {
    if (classLoaderName.contains(parent_SEP)) {
      throw new Exception("invalid classLoaderName");
    }
    ClassLoader parent = null;
    if (mavenDependencies.has(keyParent)) {
      parent = resolveParentClassloader(mavenDependencies.get(keyParent).asText(), classLoaderList);
    }
    URL[] urlList = mavenDependenciesToArtifactRequest(mavenDependencies.get(keyData));
    URLClassLoader classLoader;
    if (parent != null) {
      classLoader = new URLClassLoader(urlList, parent);
      classLoaderName =
          mavenDependencies.get(keyParent).asText().concat(parent_SEP).concat(classLoaderName);
    } else {
      classLoader = new URLClassLoader(urlList);
    }
    if (classLoaderList.containsKey(classLoaderName)) {
      removeClassLoader(classLoaderName, configList, classLoaderList);
    }
    classLoaderList.put(classLoaderName, classLoader);
    configList.put(classLoaderName, mavenDependencies);
    System.gc();
  }

  private static ClassLoader resolveParentClassloader(String parentName,
      Map<String, ClassLoader> classLoaderList) {
    if (parentName.equals(defaultSystemName)) {
      return Thread.currentThread().getContextClassLoader();
    }
    return classLoaderList.get(parentName);
  }

  public static void removeClassLoader(String classLoaderName, Map<String, JsonNode> configList,
      Map<String, ClassLoader> classLoaderList) throws Exception {
    for (String cl : classLoaderList.keySet()) {
      String[] tree = cl.split(parent_SEP);
      for (String name : tree) {
        if (name.equals(classLoaderName)) {
          classLoaderList.remove(cl);
          configList.remove(cl);
          break;
        }
      }
    }
    System.gc();
  }

  public static void initRepositorySession() {
    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, FileTransporterFactory.class);
    locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
    repositorySystem = locator.getService(RepositorySystem.class);
    systemSession = MavenRepositorySystemUtils.newSession();
    localRepo = new LocalRepository(ratataRepo);
    System.out.println(localRepo.getBasedir().getAbsolutePath());
    systemSession.setLocalRepositoryManager(
        repositorySystem.newLocalRepositoryManager(systemSession, localRepo));
  }

  private static URL[] mavenDependenciesToArtifactRequest(JsonNode data) throws Exception {
    List<URL> result = new ArrayList<URL>();
    if (data.isArray()) {
      for (JsonNode node : data) {
        result.addAll(resolveSingleRepo(node));
      }
    } else {
      result = resolveSingleRepo(data);
    }
    return result.toArray(new URL[result.size()]);
  }

  private static List<URL> resolveSingleRepo(JsonNode node) throws Exception {
    List<URL> result = new ArrayList<URL>();
    String id = defaultId, type = defaultType, url = defaultUrl;
    id = node.get(keyId) != null ? node.get(keyId).asText() : id;
    type = node.get(keyType) != null ? node.get(keyType).asText() : type;
    url = node.get(keyUrl) != null ? node.get(keyUrl).asText() : url;

    RemoteRepository remoteRepo;
    if (node.get(keyProxyURL) != null && node.get(keyProxyPort) != null
        && node.get(keyUsername) != null && node.get(keyPassword) != null) {
      String protocol = node.get(keyProxyProtocol) != null ? node.get(keyProxyProtocol).asText()
          : defaultProxyProtocol;
      Authentication auth = new AuthenticationBuilder().addUsername(node.get(keyUsername).asText())
          .addPassword(node.get(keyPassword).asText()).build();
      Proxy proxy = new Proxy(protocol, node.get(keyProxyURL).asText(),
          node.get(keyProxyPort).asInt(), (org.eclipse.aether.repository.Authentication) auth);
      remoteRepo = new RemoteRepository.Builder(id, type, url).setProxy(proxy).build();
    } else {
      remoteRepo = new RemoteRepository.Builder(id, type, url).build();
    }

    for (JsonNode dependency : node.get(keyDependencies)) {
      ArtifactRequest artifactRequest = new ArtifactRequest();
      artifactRequest.setArtifact(new DefaultArtifact(dependency.get(keyGroupId).asText(),
          dependency.get(keyArtifactId).asText(), "jar", dependency.get(keyVersion).asText()));
      artifactRequest.addRepository(remoteRepo);
      result.add(repositorySystem.resolveArtifact(systemSession, artifactRequest).getArtifact()
          .getFile().toURI().toURL());
    }
    return result;
  }

}
