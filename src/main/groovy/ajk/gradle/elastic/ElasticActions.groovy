package ajk.gradle.elastic

import de.undercouch.gradle.tasks.download.DownloadAction
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

import static ajk.gradle.elastic.ElasticPlugin.CYAN
import static ajk.gradle.elastic.ElasticPlugin.NORMAL
import static org.apache.tools.ant.taskdefs.condition.Os.FAMILY_WINDOWS
import static org.apache.tools.ant.taskdefs.condition.Os.isFamily

class ElasticActions {
  String version
  File toolsDir
  Project project
  AntBuilder ant
  File home
  Logger logger

  ElasticActions(Project project, File toolsDir, String version) {
    this.project = project
    this.toolsDir = toolsDir
    this.version = version
    this.ant = project.ant
    this.home = new File("$toolsDir/elastic")
    this.logger = Logging.getLogger(this.class)
  }

  boolean isInstalled() {
    if (!new File("$toolsDir/elastic-${version}.zip").exists()) {
      return false
    }

    if (!new File("$home/bin/elasticsearch").exists()) {
      return false
    }

    def currentVersion = getCurrentVersion()
    if (!(currentVersion?.contains(version))) {
      // cleanup when the installed version doesn't match the expected version
      logger.info("deleting $home ...")
      ant.delete(dir: home)
      logger.info("deleting $toolsDir/elastic-${version}.zip ...")
      ant.delete(file: "$toolsDir/elastic-${version}.zip")

      return false
    }

    return true
  }

  String getCurrentVersion() {
    def versionInfo = new StringBuffer()

    logger.info("${CYAN}* elastic:$NORMAL checking existing version...")
    def versionFile = new File("$home/version.txt")
    if (versionFile?.isFile() && versionFile?.canRead()) {
      versionInfo = versionFile.readLines()
    }
    logger.info("${versionInfo ?: 'unknown'}")

    return versionInfo
  }

  void install(List<String> withPlugins) {
    logger.lifecycle("${CYAN}* elastic:$NORMAL installing elastic version $version")

    String linuxUrl
    String winUrl
    if (version.startsWith("2")) {
      String baseUrl = "https://download.elasticsearch.org/elasticsearch/release/org/elasticsearch/distribution"
      linuxUrl = "${baseUrl}/tar/elasticsearch/${version}/elasticsearch-${version}.tar.gz"
      winUrl = "${baseUrl}/zip/elasticsearch/${version}/elasticsearch-${version}.zip"
    } else if (version.startsWith("5")){
      def baseUrl = "https://artifacts.elastic.co/downloads/elasticsearch"
      linuxUrl = "${baseUrl}/elasticsearch-${version}.tar.gz"
      winUrl = "${baseUrl}/elasticsearch-${version}.zip"
    } else {
      def baseUrl = "https://download.elastic.co/elasticsearch/elasticsearch"
      linuxUrl = "${baseUrl}/elasticsearch-${version}.tar.gz"
      winUrl = "${baseUrl}/elasticsearch-${version}.zip"
    }

    String elasticPackage = isFamily(FAMILY_WINDOWS) ? winUrl : linuxUrl
    File elasticFile = new File("$toolsDir/elastic-${version}.zip")
    logger.debug("Downloading from: " + elasticPackage +
    "\nto: " + elasticFile.absolutePath)

    DownloadAction elasticDownload = new DownloadAction(project)
    elasticDownload.dest(elasticFile)
    elasticDownload.src(elasticPackage)
    elasticDownload.onlyIfNewer(true)
    elasticDownload.execute()

    ant.delete(dir: home, quiet: true)
    home.mkdirs()

    if (isFamily(FAMILY_WINDOWS)) {
      ant.unzip(src: elasticFile, dest: "$home") {
        cutdirsmapper(dirs: 1)
      }
    } else {
      ant.untar(src: elasticFile, dest: "$home", compression: "gzip") {
        cutdirsmapper(dirs: 1)
      }
      ant.chmod(file: new File("$home/bin/elasticsearch"), perm: "+x")
      ant.chmod(file: new File("$home/bin/plugin"), perm: "+x")
    }

    new File("$home/version.txt").write(version)

    if (withPlugins.contains("head plugin") && !version.startsWith("5")) {
      logger.debug("* elastic: installing the head plugin")
      String plugin = "$home/bin/plugin"
      if (isFamily(FAMILY_WINDOWS)) {
        plugin += ".bat"
      }

      [
          new File(plugin),
          "--install",
          "mobz/elasticsearch-head"
      ].execute([
          "JAVA_HOME=${System.properties['java.home']}",
          "JAVA_OPTS=${System.getenv("JAVA_OPTS")}",
          "ES_HOME=$home"

      ], home)
    }
  }
}
