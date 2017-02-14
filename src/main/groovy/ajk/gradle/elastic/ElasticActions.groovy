package ajk.gradle.elastic

import de.undercouch.gradle.tasks.download.DownloadAction
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

import static ajk.gradle.elastic.ElasticPlugin.*
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

    void install() {
        logger.lifecycle("${CYAN}* elastic:$NORMAL installing elastic version $version")
        logger.lifecycle("${RED}* elastic:$NORMAL something something something")

        String linuxUrl
        String winUrl
        if (version.startsWith("2")) {
            String baseUrl = "https://download.elasticsearch.org/elasticsearch/release/org/elasticsearch/distribution"
            linuxUrl = "${baseUrl}/tar/elasticsearch/${version}/elasticsearch-${version}.tar.gz"
            winUrl = "${baseUrl}/zip/elasticsearch/${version}/elasticsearch-${version}.zip"
        } else if (version.startsWith("5")) {
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
            ant.chmod(file: new File("$home/bin/elasticsearch-plugin"), perm: "+x")
        }

        new File("$home/version.txt").write(version)
    }


    void installPlugin(String pluginUrl) {
        File pluginFile = new File("$toolsDir/plugin.zip")
        String pluginPackage = pluginUrl

        DownloadAction pluginDownload = new DownloadAction(project);
        pluginDownload.dest(pluginFile)
        pluginDownload.src(pluginPackage)
        pluginDownload.onlyIfNewer(true)
        pluginDownload.execute()

        logger.lifecycle("${GREEN}* elastic:$NORMAL  installing plugin " + pluginFile.absolutePath)
        String plugin = "$home/bin/elasticsearch-plugin"
        if (isFamily(FAMILY_WINDOWS)) {
            plugin += ".bat"
        }

        ProcessBuilder pb = new ProcessBuilder(plugin, "install", "file:///" + pluginFile.absolutePath)
        pb.directory(new File("$home"));
        pb.inheritIO()

        Process p = pb.start()
        p.in.eachLine {
            line -> logger.lifecycle("${GREEN}* elastic:$NORMAL  " + line)
        }
        p.waitFor()
    }

    void removePlugin(String pluginName) {
        logger.lifecycle("${GREEN}* elastic:$NORMAL  removing plugin " + pluginName)
        String plugin = "$home/bin/elasticsearch-plugin"
        if (isFamily(FAMILY_WINDOWS)) {
            plugin += ".bat"
        }

        ProcessBuilder pb = new ProcessBuilder(plugin, "remove", pluginName)
        pb.directory(new File("$home"));
        pb.inheritIO()

        Process p = pb.start()
        p.in.eachLine {
            line -> logger.lifecycle("${GREEN}* elastic:$NORMAL  " + line)
        }
        p.waitFor()
    }
}
