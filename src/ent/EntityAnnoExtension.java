package ent;

import org.gradle.api.file.*;
import org.gradle.api.provider.*;

/**
 * Necessary extension data for {@link EntityAnnoPlugin}.
 * @author GlFolker
 */
public interface EntityAnnoExtension{
    /** @return The location to store entity revision data. */
    DirectoryProperty getRevisionDir();

    /** @return Package name for fetched vanilla component classes, typically {@code modname.fetched}. */
    Property<String> getFetchPackage();

    /** @return Package name for "origin" component classes, typically {@code modname.entities.comp}. */
    Property<String> getGenSrcPackage();

    /** @return Package name for root generated package, typically {@code modname.gen}. */
    Property<String> getGenPackage();

    /**
     * @return Proxy prefix for GitHub API requests used by {@code :fetchComps}, prepended verbatim
     * to the request URL; empty for direct connections. Most mirrors only proxy raw file
     * downloads, not the API, so this is rarely needed.
     */
    Property<String> getApiMirror();

    /**
     * @return Proxy prefix for the raw source downloads of {@code :fetchComps}, prepended
     * verbatim to the download URL; empty for direct connections. Set this to
     * {@code https://ghproxy.net} (or a similar mirror) if raw file downloads are blocked on the
     * current network.
     */
    Property<String> getDownloadMirror();

    /** @return Attempts per downloaded file in {@code :fetchComps}, including the first one. */
    Property<Integer> getMaxRetries();

    /** @return Maximum number of concurrent downloads performed by {@code :fetchComps}. */
    Property<Integer> getThreads();
}
