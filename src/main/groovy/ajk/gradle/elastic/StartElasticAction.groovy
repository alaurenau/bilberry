package ajk.gradle.elastic

import org.codehaus.groovy.runtime.ProcessGroovyMethods
import org.gradle.api.Project
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
    List<String> withPlugins = ["head plugin"]

    private Project project

    private AntBuilder ant

    StartElasticAction(Project project) {
        this.project = project
        this.ant = project.ant
    }

    void execute() {
        File toolsDir = toolsDir ?: new File("$project.rootDir/gradle/tools")
        elasticVersion = elasticVersion ?: DEFAULT_ELASTIC_VERSION
        ElasticActions elastic = new ElasticActions(project, toolsDir, elasticVersion)

        def pidFile = new File(elastic.home, 'elastic.pid')
        if (pidFile.exists() && elasticIsRunning()) {
            println "${YELLOW}* elastic:$NORMAL ElasticSearch seems to be running at pid ${pidFile.text}"
            println "${YELLOW}* elastic:$NORMAL please check $pidFile"
            return
        }

        if (!elastic.installed) {
            elastic.install(withPlugins)
        }

        httpPort = httpPort ?: 9200
        transportPort = transportPort ?: 9300
        clusterName = clusterName ?: "elasticsearch"
        dataDir = dataDir ?: new File("$project.buildDir/elastic")
        logsDir = logsDir ?: new File("$dataDir/logs")
        println "${CYAN}* elastic:$NORMAL starting ElasticSearch at $elastic.home using http port $httpPort and tcp transport port $transportPort"
        println "${CYAN}* elastic:$NORMAL ElasticSearch data directory: $dataDir"
        println "${CYAN}* elastic:$NORMAL ElasticSearch logs directory: $logsDir"

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
        println "environment: " + environment.join(" ")
        println "arguments: " + command.join(" ")

        def process = command.execute(environment, elastic.home)

        def sout = new StringBuffer()
        def serr = new StringBuffer()
        ProcessGroovyMethods.consumeProcessOutput(process, sout, serr)

        println "${CYAN}* elastic:$NORMAL waiting for ElasticSearch to start"
        ant.waitfor(maxwait: 1, maxwaitunit: "minute", timeoutproperty: "elasticTimeout") {
            and {
                socket(server: "localhost", port: transportPort)
                ant.http(url: "http://localhost:$httpPort")
            }
        }

        if (ant.properties['elasticTimeout'] != null) {
            println "${RED}* elastic:$NORMAL could not start ElasticSearch, run log:"
            println sout
            println serr
            throw new RuntimeException("failed to start ElasticSearch")
        } else {
            println "${CYAN}* elastic:$NORMAL ElasticSearch is now up and running"
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
