package ent.task;

import arc.util.*;
import arc.util.serialization.*;
import org.gradle.api.*;
import org.gradle.api.file.*;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;

import javax.inject.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.net.http.HttpClient.*;
import java.net.http.HttpResponse.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import org.gradle.work.*;

/** {@code :fetchComps} task, downloads Mindustry component sources to a temporary directory. */
@DisableCachingByDefault(because = "Sources are downloaded from the network, so results are not reproducible")
public abstract class FetchCompsTask extends DefaultTask{
    /** @return Timeout for establishing a connection to the host. */
    private static final Duration connectTimeout = Duration.ofSeconds(10);

    /** @return Timeout for a single request, measured from after the connection is established. */
    private static final Duration requestTimeout = Duration.ofSeconds(30);

    /** @return Initial backoff between two attempts; multiplied by the attempt number. */
    private static final Duration retryBackoff = Duration.ofMillis(500);

    /** @return Upper bound of individual failures spelled out in the thrown exception message. */
    private static final int maxReportedFailures = 5;

    /** @return {@code build} property of {@code version.properties}. */
    public abstract @Input Property<String> getMindustryVersion();

    /** @return {@code type} property of {@code version.properties}. */
    public abstract @Input Property<String> getMindustryType();

    /**
     * @return Proxy prefix for GitHub API requests, prepended verbatim to the request URL;
     * empty for direct connections. Note that most mirrors only proxy raw file downloads, not the
     * API, so this is rarely needed.
     */
    public abstract @Input Property<String> getApiMirror();

    /**
     * @return Proxy prefix for raw source downloads, prepended verbatim to the download URL;
     * empty for direct connections. For example, {@code https://ghproxy.net}.
     */
    public abstract @Input Property<String> getDownloadMirror();

    /** @return Attempts per file, including the first one; at least {@code 1}. */
    public abstract @Input Property<Integer> getMaxRetries();

    /** @return Maximum number of concurrent downloads. */
    public abstract @Input Property<Integer> getThreads();

    /** @return The directory containing the unprocessed entity component source files. */
    public abstract @OutputDirectory DirectoryProperty getOutputDirectory();

    protected abstract @Inject FileSystemOperations getFileSystemOperations();

    @Inject
    public FetchCompsTask(ProjectLayout layout){
        getOutputDirectory().convention(layout.getBuildDirectory().dir("fetched-raw"));
    }

    /** @return {@code true} if the given mirror prefix is set to a non-blank value. */
    private static boolean mirrored(String mirror){
        return mirror != null && !mirror.isBlank();
    }

    /** @return {@code mirror + "/" + url}, or {@code url} if no mirror prefix is set. */
    private static String mirror(String mirror, String url){
        return mirrored(mirror) ? mirror.stripTrailing() + "/" + url : url;
    }

    /** @return The deepest non-blank message of the given throwable chain, for readable failure reports. */
    private static String rootMessage(Throwable error){
        var cause = error;
        while(cause.getCause() != null && cause.getCause() != cause)
            cause = cause.getCause();

        var message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getName() : message;
    }

    /** Sends a single GET request, turning non-{@code 200} responses into exceptions. */
    private <T, B> T request(HttpClient http, BodyHandler<B> handler, String uri, Get<T, B, Exception> get) throws Exception{
        var request = HttpRequest.newBuilder(URI.create(uri))
            .timeout(requestTimeout)
            .GET()
            .build();

        var response = http.send(request, handler);

        var in = response.body();
        try{
            if(response.statusCode() != 200)
                throw new IOException(String.format("HTTP %d from %s", response.statusCode(), response.uri()));

            return get.get(in);
        }finally{
            if(in instanceof AutoCloseable c) c.close();
        }
    }

    /** Sends a GET request, retrying with linear backoff on any failure. */
    private <T, B> T retry(HttpClient http, String what, String uri, BodyHandler<B> handler, Get<T, B, Exception> get) throws Exception{
        var attempts = Math.max(1, getMaxRetries().get());
        Exception last = null;

        for(var attempt = 1; attempt <= attempts; attempt++){
            try{
                return request(http, handler, uri, get);
            }catch(Exception e){
                last = e;

                if(attempt < attempts){
                    var backoff = retryBackoff.multipliedBy(attempt);
                    getLogger().info("Attempt {}/{} for {} failed ({}); retrying in {}ms.",
                        attempt, attempts, what, rootMessage(e), backoff.toMillis());

                    Thread.sleep(backoff.toMillis());
                }
            }
        }

        throw last;
    }

    @TaskAction
    public void fetch(){
        var mindustryVersion = getMindustryVersion().get();
        var mindustryType = getMindustryType().get();
        var apiMirror = getApiMirror().get();
        var downloadMirror = getDownloadMirror().get();

        var out = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(out));

        var outPath = out.toPath();
        try{
            Files.createDirectories(outPath);
        }catch(IOException e){
            throw new GradleException("Couldn't create output directory", e);
        }

        var http = HttpClient.newBuilder()
            .followRedirects(Redirect.NORMAL)
            .connectTimeout(connectTimeout)
            .build();

        String version = switch(mindustryType){
            case "official" -> String.format("v%s", mindustryVersion);
            case "bleeding-edge" -> {
                var uri = mirror(apiMirror, String.format("https://api.github.com/repos/Anuken/MindustryBuilds/releases/tags/%s", mindustryVersion));
                try{
                    yield retry(http, "the bleeding-edge commit hash", uri, BodyHandlers.ofString(), in -> Jval.read(in).getString("body").trim()
                    );
                }catch(Exception e){
                    throw new GradleException("Couldn't fetch bleeding-edge commit hash", e);
                }
            }
            default -> throw new GradleException(String.format("Invalid Mindustry version type `%s`", mindustryType));
        };

        var failures = new ConcurrentLinkedQueue<Failure>();
        var count = new AtomicInteger(0);
        var exec = Threads.executor("EntityAnno-Fetcher", Math.max(1, getThreads().get()));

        try{
            var uri = mirror(apiMirror, String.format("https://api.github.com/repos/Anuken/Mindustry/contents/core/src/mindustry/entities/comp?ref=%s", version));
            retry(http, "the component directory listing", uri, BodyHandlers.ofString(), in -> {
                var list = Jval.read(in).asArray();
                for(var val : list){
                    var name = val.getString("name");
                    var download = mirror(downloadMirror, val.getString("download_url"));
                    exec.submit(() -> {
                        try{
                            retry(http, name, download, BodyHandlers.ofString(), comp -> Files.writeString(outPath.resolve(name), comp));
                            count.incrementAndGet();
                        }catch(Exception e){
                            failures.add(new Failure(name, download, e));
                        }
                    });
                }
                return null;
            });
        }catch(Exception e){
            throw new GradleException("Couldn't fetch component directory contents", e);
        }

        Threads.await(exec);

        // Report every failure, not just the first one; a bare "Couldn't download `X`" is
        // indistinguishable from a missing file when the real cause is a blocked host.
        if(!failures.isEmpty()){
            var details = failures.stream()
                .limit(maxReportedFailures)
                .map(f -> String.format("  - `%s` from %s: %s", f.name(), f.uri(), rootMessage(f.error())))
                .collect(Collectors.joining(System.lineSeparator()));

            var hidden = failures.size() - maxReportedFailures;
            var overflow = hidden > 0 ? String.format("%n  ... and %d more.", hidden) : "";

            throw new GradleException(String.format(
                "Couldn't download %d of %d Mindustry component files from tag `%s`; %d succeeded.%n%s%s%s",
                failures.size(), failures.size() + count.get(), version, count.get(), details, overflow, hint(downloadMirror)
            ), failures.stream().findFirst().map(Failure::error).orElse(null));
        }

        getLogger().lifecycle("Wrote {} components.", count.get());
    }

    /** @return An actionable hint for the most common cause of widespread download failures. */
    private String hint(String downloadMirror){
        if(mirrored(downloadMirror))
            return String.format("%nThe configured download mirror `%s` was used; if it is unreachable, switch to another one.", downloadMirror);

        return String.format(
            "%nThis usually means the file host was unreachable rather than the file being absent.%n" +
            "If downloads are blocked on your network, either set a download mirror in `gradle.properties`:%n" +
            "    downloadMirror = https://ghproxy.net%n" +
            "or route Gradle through a proxy:%n" +
            "    systemProp.https.proxyHost = 127.0.0.1%n" +
            "    systemProp.https.proxyPort = 7890"
        );
    }

    private interface Get<T, B, E extends Exception>{
        T get(B body) throws E;
    }

    private record Failure(String name, String uri, Throwable error){}
}
