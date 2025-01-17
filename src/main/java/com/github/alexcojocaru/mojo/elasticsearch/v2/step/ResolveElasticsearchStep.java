package com.github.alexcojocaru.mojo.elasticsearch.v2.step;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URL;
import java.util.UUID;

import com.github.alexcojocaru.mojo.elasticsearch.v2.util.ArchiveUtil;
import com.google.common.base.Joiner;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;

import com.github.alexcojocaru.mojo.elasticsearch.v2.ClusterConfiguration;
import com.github.alexcojocaru.mojo.elasticsearch.v2.ElasticsearchArtifact;
import com.github.alexcojocaru.mojo.elasticsearch.v2.InstanceConfiguration;
import com.github.alexcojocaru.mojo.elasticsearch.v2.configuration.ArtifactException;
import com.github.alexcojocaru.mojo.elasticsearch.v2.configuration.PluginArtifactResolver;
import com.github.alexcojocaru.mojo.elasticsearch.v2.util.FilesystemUtil;
import com.github.alexcojocaru.mojo.elasticsearch.v2.util.VersionUtil;

/**
 * Download and unpack elasticsearch into the destination directory.
 *
 * @author Alex Cojocaru
 */
public class ResolveElasticsearchStep
        implements InstanceStep
{
    private final String ELASTICSEARCH_FILE_PARAM ="/%s";

    private final String ELASTICSEARCH_DOWNLOAD_URL =
            "https://artifacts.elastic.co/downloads/elasticsearch/%s";
    
    @Override
    public void execute(InstanceConfiguration config)
    {
        File unpackDirectory = null;
        try
        {
            File artifact = resolveArtifact(config.getClusterConfiguration());
            
            unpackDirectory = unpackToElasticsearchDirectory(artifact, config);

            setupElasticsearchConf(config);
        }
        catch (ArtifactException | IOException e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            cleanUp(unpackDirectory, config.getClusterConfiguration());
        }
    }
    
    /**
     * Resolve the artifact and return a file reference to the local file.
     */
    private File resolveArtifact(ClusterConfiguration config)
            throws ArtifactException, IOException
    {
        String flavour = config.getFlavour();
        String version = config.getVersion();
        String artifactId = getArtifactId(flavour, version);
        String classifier = getArtifactClassifier(version);
        String type = getArtifactType(version);

        ElasticsearchArtifact artifactReference = new ElasticsearchArtifact(artifactId, version, classifier, type);
        config.getLog().debug("Artifact ref: " + artifactReference);

        PluginArtifactResolver artifactResolver = config.getArtifactResolver();
        try
        {
            config.getLog().debug("Resolving artifact against the local maven repo (stage 1)");
            return artifactResolver
                    .resolveArtifact(artifactReference.getArtifactCoordinates());
        }
        catch (ArtifactException e)
        {
            config.getLog().debug("Artifact not found; downloading and installing it");

            File tempFile = downloadArtifact(artifactReference, config);

            config.getLog().debug("Installing " + tempFile + " in the local maven repo");
            config.getArtifactInstaller().installArtifact(
                    artifactReference.getGroupId(),
                    artifactReference.getArtifactId(),
                    artifactReference.getVersion(),
                    artifactReference.getClassifier(),
                    artifactReference.getType(),
                    tempFile);
            
            config.getLog().debug("Resolving artifact against the local maven repo (stage 2)");
            return artifactResolver
                    .resolveArtifact(artifactReference.getArtifactCoordinates());
        }
    }
    
    private String getArtifactId(String flavour, String version)
    {
        if (VersionUtil.isBetween_5_0_0_and_6_2_x(version)) // no flavour for ES under 6.3.0
        {
            return "elasticsearch";
        }
        else if (StringUtils.isEmpty(flavour))
        {
            return "elasticsearch-oss";
        }
        else if ("default".equals(flavour))
        {
            return "elasticsearch";
        }
        else
        {
            return String.format("elasticsearch-%s", flavour);
        }
    }

    private String getArtifactClassifier(String version)
    {
        if (VersionUtil.isEqualOrGreater_7_0_0(version))
        {
            if (SystemUtils.IS_OS_WINDOWS)
            {
                return "windows-x86_64";
            }
            else if (SystemUtils.IS_OS_MAC)
            {
                return "darwin-x86_64";
            }
            else if (SystemUtils.IS_OS_LINUX)
            {
                return "linux-x86_64";
            }
            else {
                throw new IllegalStateException("Unknown OS, cannot determine the Elasticsearch classifier.");
            }
        }
        else // No classifier for ES below 7.0.0
        {
            return null;
        }
    }

    private String getArtifactType(String version)
    {
        if (VersionUtil.isEqualOrGreater_7_0_0(version))
        {
            if (SystemUtils.IS_OS_WINDOWS)
            {
                return "zip";
            }
            else if (SystemUtils.IS_OS_MAC)
            {
                return "tar.gz";
            }
            else if (SystemUtils.IS_OS_LINUX)
            {
                return "tar.gz";
            }
            else {
                throw new IllegalStateException("Unknown OS, cannot determine the Elasticsearch classifier.");
            }
        }
        else // Only a single artifact type below 7.0.0
        {
            return "zip";
        }
    }

    /**
     * Download the artifact from the download repository.
     * @param artifactReference
     * @param config
     * @return the downloaded file
     * @throws IOException
     */
    private File downloadArtifact(
            ElasticsearchArtifact artifactReference,
            ClusterConfiguration config)
            throws IOException
    {
        String filename = Joiner.on("-").skipNulls()
                .join(
                        artifactReference.getArtifactId(),
                        artifactReference.getVersion(),
                        artifactReference.getClassifier() // May be null
                )
                + "." + artifactReference.getType();

        File tempFile = new File(FilesystemUtil.getTempDirectory(), filename);
        tempFile.deleteOnExit();
        FileUtils.deleteQuietly(tempFile);

        URL downloadUrl = new URL(
                StringUtils.isBlank(config.getDownloadUrl())
                        ? String.format(ELASTICSEARCH_DOWNLOAD_URL, filename)
                        : config.getDownloadUrl().endsWith(ELASTICSEARCH_FILE_PARAM) 
                            ? String.format(config.getDownloadUrl(), filename) 
                            : config.getDownloadUrl());

        config.getLog().debug("Downloading " + downloadUrl + " to " + tempFile);
        FileUtils.copyURLToFile(downloadUrl, tempFile);
        
        return tempFile;
    }
    
    private void cleanUp(File unpackDirectory, ClusterConfiguration config)
    {
        if (unpackDirectory != null)
        {
            try
            {
                FileUtils.deleteDirectory(unpackDirectory);
            }
            catch (IOException e)
            {
                config.getLog().error(
                        String.format(
                            "Could not delete Elasticsearch upack directory : ",
                            unpackDirectory.getAbsolutePath()),
                        e);
            }
        }
    }

    private void setupElasticsearchConf(InstanceConfiguration config) throws IOException
    {
        String pathConf = config.getClusterConfiguration().getPathConf();
        if (pathConf != null && !pathConf.isEmpty())
        {
            // Merge the user-defined config directory with the default one
            // This allows user to omit some configuration files (jvm.options for instance)
            File baseDir = new File(config.getBaseDir());
            FileUtils.copyDirectory(new File(pathConf), new File(baseDir, "config"));
        }
    }
    
    private File unpackToElasticsearchDirectory(File artifact, InstanceConfiguration config)
            throws IOException
    {
        File unpackDirectory = getUnpackDirectory();
        ArchiveUtil.autodetectAndExtract(artifact, unpackDirectory);
        File baseDir = new File(config.getBaseDir());
        moveToElasticsearchDirectory(unpackDirectory, baseDir);

        return unpackDirectory;
    }

    private void moveToElasticsearchDirectory(File src, File dest) throws IOException
    {
        File[] files = src.listFiles(new FileFilter()
        {
            @Override
            public boolean accept(final File file)
            {
                return file.isDirectory();
            }
        });

        // should only be one
        FilesystemUtil.copyRecursively(files[0].toPath(), dest.toPath());
    }

    protected File getUnpackDirectory()
    {
        File tempDir = FilesystemUtil.getTempDirectory();
        File upackDirectory = new File(tempDir, UUID.randomUUID().toString());
        upackDirectory.mkdirs();
        return upackDirectory;
    }

}
