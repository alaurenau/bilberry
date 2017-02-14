package ajk.gradle.elastic

import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

import static ajk.gradle.elastic.ElasticPlugin.*
import static org.apache.http.client.fluent.Executor.newInstance
import static org.apache.http.client.fluent.Request.Post
import static org.apache.tools.ant.taskdefs.condition.Os.FAMILY_WINDOWS
import static org.apache.tools.ant.taskdefs.condition.Os.isFamily

class StopElasticAction {

    @Input
    @Optional
    private Integer httpPort

    @Input
    @Optional
    File toolsDir

    @Input
    @Optional
    String elasticVersion

    @Input
    @Optional
    String pluginName = "placeholder-name"

    @Input
    @Optional
    boolean hasPlugin = false


    private AntBuilder ant
    private Project project
    private Logger logger

    StopElasticAction(Project project) {
        this.project = project
        this.ant = project.ant
        this.logger = Logging.getLogger(this.class)
    }

    void execute() {
        File toolsDir = toolsDir ?: new File("$project.rootDir/gradle/tools")
        ElasticActions elastic = new ElasticActions(project, toolsDir, elasticVersion ?: DEFAULT_ELASTIC_VERSION)

        logger.lifecycle("${CYAN}* elastic:$NORMAL Stopping ElasticSearch")

        try {
            def pidFile = new File(elastic.home, 'elastic.pid')
            if (elastic.version.startsWith("2") || elastic.version.startsWith("5")) {
                if (!pidFile.exists()) {
                    logger.error("${RED}* elastic:$NORMAL ${pidFile} not found, " +
                            "could not stop ElasticSearch, please check manually!")
                    return
                }
                def elasticPid = pidFile.text
                logger.lifecycle("${CYAN}* elastic:$NORMAL Going to kill pid $elasticPid")
                if (isFamily(FAMILY_WINDOWS)) {
                    "cmd /c \"taskkill /f /pid $elasticPid\"".execute()
                } else {
                    "kill $elasticPid".execute()
                }
            } else {
                newInstance().execute(Post("http://localhost:${httpPort ?: 9200}/_shutdown"))
            }

            logger.lifecycle("${CYAN}* elastic:$NORMAL Waiting for ElasticSearch to shutdown")
            ant.waitfor(maxwait: 2, maxwaitunit: "minute", timeoutproperty: "elasticTimeout") {
                not {
                    ant.http(url: "http://localhost:$httpPort")
                }
            }

            if (ant.properties['elasticTimeout'] != null) {
                logger.error("${RED}* elastic:$NORMAL Could not stop ElasticSearch")
                throw new RuntimeException("Failed to stop ElasticSearch")
            } else {
                if (isFamily(FAMILY_WINDOWS)) {
                    pidFile.delete()
                }
                logger.lifecycle("${CYAN}* elastic:$NORMAL ElasticSearch is now down")
            }
            if(hasPlugin){
                elastic.removePlugin(pluginName)
            }
        } catch (ConnectException e) {
            logger.error("${CYAN}* elastic:$YELLOW Unable to stop elastic on http port ${httpPort ?: 9200}, " +
                    "${e.message}$NORMAL")
        }
    }
}
