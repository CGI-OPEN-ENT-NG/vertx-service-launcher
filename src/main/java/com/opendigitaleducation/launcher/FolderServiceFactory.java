package com.opendigitaleducation.launcher;

import io.vertx.core.*;
import io.vertx.service.ServiceVerticleFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class FolderServiceFactory extends ServiceVerticleFactory {

    protected static final String SERVICES_PATH = "vertx.services.path";
    protected static final String FACTORY_PREFIX = "folderService";

    private Vertx vertx;
    private String servicesPath;
    private ConcurrentMap<String, String> jarsInPath = new ConcurrentHashMap<>();

    @Override
    public void init(Vertx vertx) {
        this.vertx = vertx;
        this.servicesPath = System.getProperty(SERVICES_PATH);
        try {
            List<String> jars = this.vertx.fileSystem().readDirBlocking(servicesPath, ".*-fat.jar");
            for (String jarPath : jars) {
                final String jarName;
                if (jarPath.contains(File.separator)) {
                    jarName = jarPath.substring(jarPath.lastIndexOf(File.separatorChar) + 1);
                } else {
                    jarName = jarPath;
                }
                jarsInPath.putIfAbsent(jarName.replaceFirst("-fat.jar", ""), jarPath);
            }
        } catch (RuntimeException e) {
            // TODO add log
        }
    }

    @Override
    public void resolve(String id, DeploymentOptions deploymentOptions, ClassLoader classLoader, Future<String> resolution) {
        if (id == null || !id.startsWith(prefix())) {
            resolution.fail("Invalid identifier : " + id);
            return;
        }
        final String identifier = id.substring(prefix().length() + 1);
        String[] artifact = identifier.split("~");
        if (artifact.length != 3) {
           resolution.fail("Invalid artifact : " + identifier);
           return;
        }

        final String servicePath = servicesPath + File.separator +
            identifier + File.separator;
        vertx.fileSystem().exists(servicePath, ar -> {
            if (ar.succeeded() && ar.result()) {
                deploy(identifier, deploymentOptions, classLoader, resolution, artifact, servicePath);
            } else {
                final String jar = jarsInPath.get(identifier);
                if (jar != null) {
                    try {
                        unzipJar(jar, servicePath);
                        deploy(identifier, deploymentOptions, classLoader, resolution, artifact, servicePath);
                    } catch (IOException e) {
                        resolution.fail(e);
                    }
                } else {
                    resolution.fail("Service not found : " + identifier);
                }
            }
		});
    }

    private void deploy(String identifier, DeploymentOptions deploymentOptions, ClassLoader classLoader, Future<String> resolution, String[] artifact, String servicePath) {
        vertx.fileSystem().readFile(servicePath + "META-INF" + File.separator + "MANIFEST.MF", ar -> {
			if (ar.succeeded()) {
                Scanner s = new Scanner(ar.result().toString());
                String id = null;
                while (s.hasNextLine()) {
                    final String line = s.nextLine();
                    if (line.contains("Main-Verticle:")) {
                        String [] item = line.split(":");
                        if (item.length == 3) {
                            id = item[2];
                            deploymentOptions.setExtraClasspath(Collections.singletonList(servicePath));
                            deploymentOptions.setIsolationGroup("__vertx_folder_" + artifact[1]);
                            try {
                                URLClassLoader urlClassLoader = new URLClassLoader(
                                    new URL[]{new URL("file://" + servicePath )}, classLoader);
                                FolderServiceFactory.super.resolve(id, deploymentOptions, urlClassLoader, resolution);
                            } catch (MalformedURLException e) {
                                resolution.fail(e);
                            }
                        } else {
                            resolution.fail("Invalid service identifier : " + line);
                        }
                        break;
                    }
                }
                s.close();
                if (id == null && !resolution.isComplete()) {
                    resolution.fail("Service not found : " + identifier);
                }
            } else {
                resolution.fail(ar.cause());
            }
		});
    }

    // TODO replace by non-blocking method
    private void unzipJar(String jarFile, String destDir) throws IOException {
        JarFile jar = new JarFile(jarFile);
        Enumeration enumEntries = jar.entries();
        while (enumEntries.hasMoreElements()) {
            JarEntry file = (JarEntry) enumEntries.nextElement();
            File f = new File(destDir + File.separator + file.getName());
            if (file.isDirectory()) { // if its a directory, create it
                f.mkdirs();
                continue;
            }
            InputStream is = jar.getInputStream(file); // get the input stream
            FileOutputStream fos = new FileOutputStream(f);
            while (is.available() > 0) {  // write contents of 'is' to 'fos'
                fos.write(is.read());
            }
            fos.close();
            is.close();
        }
        jar.close();
    }

    @Override
    public String prefix() {
        return FACTORY_PREFIX;
    }

}
