package net.adoptium.release

import groovy.cli.picocli.CliBuilder
import groovy.cli.picocli.OptionAccessor
import groovy.transform.CompileStatic
import org.kohsuke.github.*
import org.kohsuke.github.extras.ImpatientHttpConnector

import java.nio.file.Files
import java.util.concurrent.TimeUnit

@CompileStatic
class UploadAdoptReleaseFiles {

    private final String tag
    private final String description
    private final boolean release
    private final List<File> files
    private final String version
    private final String server
    private final String org
    private final String edition

    UploadAdoptReleaseFiles(String tag, String description, boolean release, String version, String server, String org, String edition, List<File> files) {
        this.tag = tag
        this.description = description
        this.release = release
        this.files = files
        this.version = version
        this.server = server
        this.org = org
        this.edition = edition
    }

    void release() {
        def grouped = files.groupBy {
            switch (it.getName()) {
                case ~/.*semeru.*/: "ibm"; break;
            }
        }
        GHRepository repo = getRepo(edition)
        println("REPO:$repo")
        GHRelease release = getRelease(repo)
        println("RELEASE:$release")
        uploadFiles(release, grouped.get("ibm"))
    }

    private GHRepository getRepo(String vendor) {
        String token = System.getenv("GITHUB_TOKEN")
        if (token == null) {
            System.err.println("Could not find GITHUB_TOKEN")
            System.exit(1)
        }

        println("Using Github server:'${server}'")
        GitHub github = GitHub.connectUsingOAuth(server, token)

        github
                .setConnector(new ImpatientHttpConnector(new HttpConnector() {
                    HttpURLConnection connect(URL url) throws IOException {
                        return (HttpURLConnection) url.openConnection()
                    }
                },
                        (int) TimeUnit.SECONDS.toMillis(120),
                        (int) TimeUnit.SECONDS.toMillis(120)))

        println("Using Github org:'${org}'")
        // jdk11 => 11
        def numberVersion = version.replaceAll(/[^0-9]/, "")
        def repoName = "${org}/temurin${numberVersion}-binaries"

        if (vendor == "open") {
            repoName = "${org}/semeru${numberVersion}-binaries"
        } else if (vendor == "certified") {
            repoName = "${org}/semeru${numberVersion}-certified-binaries"
        } else if (vendor == "ea") {
            repoName = "${org}/semeru${numberVersion}-ea-binaries"
        }
        println("reponame:${repoName}")
        return github.getRepository(repoName)
    }

    private void uploadFiles(GHRelease release, List<File> files) {
        List<GHAsset> assets = release.getAssets()
        files.each { file ->
            // Delete existing asset
            assets
                    .find({ it.name == file.name })
                    .each { GHAsset existing ->
                        println("Updating ${existing.name}")
                        existing.delete()
                    }

            println("Uploading ${file.name}")
            release.uploadAsset(file, Files.probeContentType(file.toPath()))
        }
    }

    private GHRelease getRelease(GHRepository repo) {
        println("get releases")
        GHRelease release = repo
                .getReleaseByTagName(tag)
        println("got releases")
        if (release == null) {
            println("releases was null")
            release = repo
                    .createRelease(tag)
                    .body(description)
                    .draft(true)
                    .name(tag)
                    .prerelease(!this.release)
                    .create()
        }
        println("Done getting release")
        return release
    }
}


static void main(String[] args) {
    OptionAccessor options = parseArgs(args)

    List<File> files = options.arguments()
            .collect { new File(it) }

    new UploadAdoptReleaseFiles(
            options.t,
            options.d,
            options.r,
            options.v,
            options.s,
            options.o,
            options.p,
            files,
    ).release()
}

private OptionAccessor parseArgs(String[] args) {

    CliBuilder cliBuilder = new CliBuilder()

    cliBuilder
            .with {
                v longOpt: 'version', type: String, args: 1, 'JDK version'
                t longOpt: 'tag', type: String, args: 1, 'Tag name'
                d longOpt: 'description', type: String, args: 1, 'Release description'
                r longOpt: 'release', 'Is a release build'
                h longOpt: 'help', 'Show usage information'
                s longOpt: 'server', type: String, args: 1, optionalArg: true, defaultValue: 'https://api.github.com', 'Github server'
                o longOpt: 'org', type: String, args: 1, optionalArg: true, defaultValue: 'adoptium', 'Github org'
                p longOpt: 'edition', type: String, args: 1, optionalArg: true, defaultValue: 'open', 'Semeru Edition'
            }

    def options = cliBuilder.parse(args)
    if (options.v && options.t && options.d) {
        return options
    }
    cliBuilder.usage()
    System.exit(1)
    return null
}
