package ajk.gradle.elastic

import org.codehaus.groovy.runtime.ProcessGroovyMethods
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

import static ajk.gradle.elastic.ElasticPlugin.*
import static org.apache.tools.ant.taskdefs.condition.Os.FAMILY_WINDOWS
import static org.apache.tools.ant.taskdefs.condition.Os.isFamily

class StartElasticAction {

    @Input
    @Optional
    String elasticVersion

    @Input
    @Optional
    int httpPort

    @Input
    @Optional
    int transportPort

    @Input
    @Optional
    String clusterName

    @Input
    @Optional
    File toolsDir

    @Input
    @Optional
    File dataDir

    @Input
    @Optional
    File logsDir

    @Input
    @Optional
    String javaOpts

    @Input
    @Optional
    boolean hasPlugin = false;

    @Input
    @Optional
    String pluginUrl;

    private Project project
    private AntBuilder ant
    private Logger logger

    StartElasticAction(Project project) {
        this.project = project
        this.ant = project.ant
        this.logger = Logging.getLogger(this.class)

        this.elasticVersion = elasticVersion ?: DEFAULT_ELASTIC_VERSION
        this.httpPort = httpPort ?: 9200
        this.transportPort = transportPort ?: 9300
        this.clusterName = clusterName ?: "elasticsearch"
        this.dataDir = dataDir ?: new File("$project.buildDir/elastic")
        this.logsDir = logsDir ?: new File("$dataDir/logs")
    }

    void execute() {
        File toolsDir = toolsDir ?: new File("$project.rootDir/gradle/tools")

        ElasticActions elastic = new ElasticActions(project, toolsDir, elasticVersion)

        def pidFile = new File(elastic.home, 'elastic.pid')
        if (elasticIsRunning()) {
            logger.lifecycle("${YELLOW}* elastic:$NORMAL ElasticSearch seems to be running at http://localhost:$httpPort")
            if (pidFile.exists()) {
                logger.lifecycle("${YELLOW}* elastic:$NORMAL ElasticSearch process id: ${pidFile.text}")
            }
            return
        }

        if (!elastic.installed) {
            elastic.install()
        }
        if(hasPlugin){
            elastic.installPlugin(pluginUrl)
        }

        logger.lifecycle("${CYAN}* elastic:$NORMAL Starting ElasticSearch at $elastic.home, " +
                "http port: $httpPort, tcp transport port: $transportPort")
        logger.lifecycle("${CYAN}* elastic:$NORMAL ElasticSearch data directory: $dataDir")
        logger.lifecycle("${CYAN}* elastic:$NORMAL ElasticSearch logs directory: $logsDir")

        ant.delete(failonerror: true, dir: dataDir)
        ant.delete(failonerror: true, dir: logsDir)
        logsDir.mkdirs()
        dataDir.mkdirs()

        File esScript = new File("${elastic.home}/bin/elasticsearch${isFamily(FAMILY_WINDOWS) ? '.bat' : ''}")

        def paramPrefix
        def command = [esScript.absolutePath]
        def environment = []
        if (elasticVersion.startsWith("5")) {
            environment.add("ES_JAVA_OPTS=-Xms128m -Xmx512m")
            paramPrefix = '-E'
        } else {
            environment.addAll(["ES_MAX_MEM=512m", "ES_MIN_MEM=128m"])
            paramPrefix = '-Des.'
            command.add("${paramPrefix}discovery.zen.ping.multicast.enabled=false")
        }

        if (javaOpts != null) {
            environment += [
                "JAVA_OPTS=${javaOpts}"
            ]
        }

        environment += [
                "JAVA_HOME=${System.properties['java.home']}",
                "ES_HOME=$elastic.home"
        ]
        command += [
                "${paramPrefix}http.port=$httpPort",
                "${paramPrefix}transport.tcp.port=$transportPort",
                "${paramPrefix}cluster.name=$clusterName",
                "${paramPrefix}path.data=$dataDir",
                "${paramPrefix}path.logs=$logsDir",
                "${paramPrefix}network.host=127.0.0.1",
                "-p", "${pidFile}"
        ]

        if (isFamily(FAMILY_WINDOWS)) {
            environment += [
                    "TEMP=${System.env['TEMP']}"
            ]
        }
        logger.debug("Environment variables: " + environment.join(" "))
        logger.debug("Arguments: " + command.join(" "))

        def process = command.execute(environment, elastic.home)

        def stdOut = new StringBuffer()
        def stdErr = new StringBuffer()
        ProcessGroovyMethods.consumeProcessOutput(process, stdOut, stdErr)

        logger.lifecycle("${CYAN}* elastic:$NORMAL Waiting for ElasticSearch to start")
        ant.waitfor(maxwait: 1, maxwaitunit: "minute", timeoutproperty: "elasticTimeout") {
            and {
                socket(server: "localhost", port: transportPort)
                ant.http(url: "http://localhost:$httpPort")
            }
        }

        if (ant.properties['elasticTimeout'] != null) {
            logger.error("${RED}* elastic:$NORMAL Could not start ElasticSearch, run log:")
            logger.error(stdOut.toString())
            logger.error(stdErr.toString())
            throw new RuntimeException("Failed to start ElasticSearch")
        } else {
            logger.lifecycle("${CYAN}* elastic:$NORMAL ElasticSearch is now up and running")
        }
    }

    boolean elasticIsRunning() {
        ant.waitfor(maxwait: 3, maxwaitunit: "second", timeoutproperty: "elasticIsDown") {
            and {
                ant.http(url: "http://localhost:$httpPort")
            }
        }
        return ant.properties['elasticIsDown'] == null
    }
}
