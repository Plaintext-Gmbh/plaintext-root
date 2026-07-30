/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Controller
@Slf4j
public class XhtmlDebugController {

    @Autowired
    private ResourcePatternResolver resourcePatternResolver;

    @GetMapping("/debug/xhtml-resources")
    @ResponseBody
    public String debugXhtmlResources() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<title>XHTML Resources Debug</title>");
        html.append("<style>");
        html.append("body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 20px; background: #f5f5f5; }");
        html.append("h1 { color: #333; border-bottom: 3px solid #4CAF50; padding-bottom: 10px; margin-bottom: 20px; }");
        html.append("h2 { color: #555; margin-top: 20px; margin-bottom: 15px; display: flex; align-items: center; }");
        html.append("h3 { color: #666; margin-top: 15px; margin-bottom: 10px; font-size: 1.1em; }");
        html.append(".location-section { background: white; padding: 25px; margin: 20px 0; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }");
        html.append(".count { background: #4CAF50; color: white; padding: 4px 12px; border-radius: 12px; font-weight: 600; font-size: 0.85em; display: inline-block; min-width: 24px; text-align: center; margin-left: 8px; }");
        html.append(".count-small { background: #2196F3; color: white; padding: 2px 8px; border-radius: 10px; font-weight: 600; font-size: 0.75em; display: inline-block; min-width: 20px; text-align: center; margin-left: 8px; }");
        html.append(".resource-table { width: 100%; border-collapse: collapse; margin-top: 10px; }");
        html.append(".resource-table th { background: #f5f5f5; padding: 10px; text-align: left; font-weight: 600; font-size: 0.9em; border-bottom: 2px solid #ddd; }");
        html.append(".resource-table td { padding: 10px; border-bottom: 1px solid #eee; font-size: 0.9em; vertical-align: top; }");
        html.append(".resource-table tr:hover { background: #f9f9f9; }");
        html.append(".file-path { font-family: 'Consolas', 'Monaco', monospace; color: #333; font-size: 0.9em; word-break: break-all; }");
        html.append(".file-size { color: #666; font-size: 0.85em; }");
        html.append(".module-badge { background: #E3F2FD; color: #1976D2; padding: 4px 10px; border-radius: 4px; font-size: 0.85em; font-weight: 600; display: inline-block; }");
        html.append(".jar-badge { background: #FFF3E0; color: #F57C00; padding: 4px 10px; border-radius: 4px; font-size: 0.85em; font-weight: 600; display: inline-block; }");
        html.append(".summary { background: linear-gradient(135deg, #E3F2FD 0%, #BBDEFB 100%); padding: 20px; border-radius: 8px; margin-bottom: 20px; border-left: 4px solid #2196F3; }");
        html.append(".summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; margin-top: 15px; }");
        html.append(".summary-item { background: white; padding: 15px; border-radius: 6px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }");
        html.append(".summary-label { font-size: 0.85em; color: #666; margin-bottom: 5px; }");
        html.append(".summary-value { font-size: 1.5em; font-weight: bold; color: #1976D2; }");
        html.append(".info-box { background: #FFF9C4; padding: 20px; border-radius: 8px; margin: 20px 0; border-left: 4px solid #FBC02D; }");
        html.append("code { background: #f4f4f4; padding: 3px 8px; border-radius: 4px; font-family: 'Consolas', monospace; font-size: 0.9em; }");
        html.append(".strategy { margin: 10px 0; padding: 15px; background: white; border-left: 3px solid #9C27B0; border-radius: 4px; }");
        html.append(".strategy strong { color: #9C27B0; }");
        html.append(".pattern-list { list-style: none; padding: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 10px; }");
        html.append(".pattern-list li { padding: 10px 15px; background: #f9f9f9; border-left: 3px solid #2196F3; font-family: monospace; font-size: 0.9em; border-radius: 4px; }");
        html.append("</style>");
        html.append("</head><body>");

        html.append("<h1>📄 XHTML Resources Debug Information</h1>");

        // JSF Hot-Reload Status
        html.append("<div class='location-section'>");
        html.append("<h2>⚙️ JSF Hot-Reload Configuration</h2>");
        html.append("<div style='background: white; padding: 15px; border-radius: 6px; border-left: 4px solid #4CAF50;'>");
        html.append("<strong>ViewHandler:</strong> DevelopmentViewHandler (registered in faces-config.xml)<br>");
        html.append("<strong>ResourceHandler:</strong> DevelopmentResourceHandler (registered in faces-config.xml)<br>");
        html.append("<span style='color: #666;'>Hot-reload enabled for XHTML files in development mode</span>");
        html.append("</div>");
        html.append("</div>");

        // Loading strategies explanation
        html.append("<div class='info-box'>");
        html.append("<h3>📚 XHTML Loading Strategies in JSF/Spring Boot</h3>");
        html.append("<div class='strategy'><strong>1. META-INF/resources/</strong><br>");
        html.append("Standard location for web resources in JARs (Servlet 3.0+). JSF automatically scans here.</div>");
        html.append("<div class='strategy'><strong>2. WEB-INF/</strong><br>");
        html.append("Protected directory for web resources in traditional WAR deployments.</div>");
        html.append("<div class='strategy'><strong>3. /resources/ in classpath</strong><br>");
        html.append("General classpath resources location.</div>");
        html.append("<div class='strategy'><strong>4. Local filesystem</strong><br>");
        html.append("Development mode: Direct file access for hot-reload.</div>");
        html.append("</div>");

        // Search patterns
        String[] patterns = {
            "classpath*:META-INF/resources/**/*.xhtml",
            "classpath*:META-INF/resources/**/*.html",
            "classpath*:META-INF/resources/**/*.jsf",
            "classpath*:resources/**/*.xhtml",
            "classpath*:**/WEB-INF/**/*.xhtml",
            "classpath*:templates/**/*.xhtml"
        };

        Map<String, List<ResourceInfo>> resourcesByLocation = new TreeMap<>();
        int totalResources = 0;

        log.info("Scanning resources from Classpath");
        // Standard classpath scanning
        for (String pattern : patterns) {
            try {
                log.info("Scanning pattern: {}", pattern);
                Resource[] resources = resourcePatternResolver.getResources(pattern);

                for (Resource resource : resources) {
                    try {
                        ResourceInfo info = analyzeResource(resource);
                        resourcesByLocation
                            .computeIfAbsent(info.locationType, k -> new ArrayList<>())
                            .add(info);
                        totalResources++;
                    } catch (Exception e) {
                        log.warn("Error analyzing resource: {}", resource, e);
                    }
                }
            } catch (IOException e) {
                log.warn("Error scanning pattern {}: {}", pattern, e.getMessage());
            }
        }

        // Summary
        html.append("<div class='summary'>");
        html.append("<h2 style='margin-top: 0; border: none;'>📊 Summary</h2>");
        html.append("<div class='summary-grid'>");

        // Total resources
        html.append("<div class='summary-item'>");
        html.append("<div class='summary-label'>Total Resources</div>");
        html.append("<div class='summary-value'>").append(totalResources).append("</div>");
        html.append("</div>");

        // By location type
        for (String locationType : resourcesByLocation.keySet()) {
            int count = resourcesByLocation.get(locationType).size();
            html.append("<div class='summary-item'>");
            html.append("<div class='summary-label'>").append(locationType).append("</div>");
            html.append("<div class='summary-value'>").append(count).append("</div>");
            html.append("</div>");
        }

        html.append("</div></div>");

        // Group by location type
        for (Map.Entry<String, List<ResourceInfo>> entry : resourcesByLocation.entrySet()) {
            String locationType = entry.getKey();
            List<ResourceInfo> resources = entry.getValue();

            // Sort by path
            resources.sort(Comparator.comparing(r -> r.relativePath));

            html.append("<div class='location-section'>");
            html.append("<h2>");
            if (locationType.contains("JAR")) {
                html.append("📦 ");
            } else if (locationType.contains("File")) {
                html.append("📁 ");
            } else {
                html.append("📄 ");
            }
            html.append(locationType);
            html.append("<span class='count'>").append(resources.size()).append("</span>");
            html.append("</h2>");

            // Group by module/JAR
            Map<String, List<ResourceInfo>> byModule = resources.stream()
                .collect(Collectors.groupingBy(r -> r.module));

            for (Map.Entry<String, List<ResourceInfo>> moduleEntry : byModule.entrySet()) {
                String module = moduleEntry.getKey();
                List<ResourceInfo> moduleResources = moduleEntry.getValue();

                html.append("<h3>");
                if (locationType.contains("JAR")) {
                    html.append("<span class='jar-badge'>").append(module).append("</span>");
                } else {
                    html.append("<span class='module-badge'>").append(module).append("</span>");
                }
                html.append("<span class='count-small'>").append(moduleResources.size()).append("</span>");
                html.append("</h3>");

                html.append("<table class='resource-table'>");
                html.append("<thead><tr>");
                html.append("<th style='width: 40%;'>File</th>");
                html.append("<th style='width: 15%;'>Size</th>");
                if (locationType.contains("JAR")) {
                    html.append("<th style='width: 45%;'>Source JAR</th>");
                } else {
                    html.append("<th style='width: 45%;'>Module Path</th>");
                }
                html.append("</tr></thead>");
                html.append("<tbody>");

                for (ResourceInfo info : moduleResources) {
                    html.append("<tr>");

                    // File name
                    html.append("<td><div class='file-path'>").append(htmlEscape(info.relativePath)).append("</div></td>");

                    // Size
                    html.append("<td>");
                    if (info.fileSize > 0) {
                        html.append("<span class='file-size'>").append(formatBytes(info.fileSize)).append("</span>");
                    } else {
                        html.append("<span style='color: #999;'>-</span>");
                    }
                    html.append("</td>");

                    // Source
                    html.append("<td>");
                    if (!info.sourceJar.isEmpty()) {
                        html.append("<code style='font-size: 0.8em;'>").append(htmlEscape(info.sourceJar)).append("</code>");
                    } else {
                        html.append("<code style='font-size: 0.8em;'>").append(htmlEscape(module)).append("</code>");
                    }
                    html.append("</td>");

                    html.append("</tr>");
                }
                html.append("</tbody></table>");
            }
            html.append("</div>");
        }

        // Search patterns used
        html.append("<div class='location-section'>");
        html.append("<h2>🔍 Search Patterns Used<span class='count'>").append(patterns.length).append("</span></h2>");
        html.append("<ul class='pattern-list'>");
        for (String pattern : patterns) {
            html.append("<li>").append(htmlEscape(pattern)).append("</li>");
        }
        html.append("</ul>");
        html.append("</div>");

        html.append("</body></html>");
        return html.toString();
    }

    private ResourceInfo analyzeResource(Resource resource) throws IOException {
        ResourceInfo info = new ResourceInfo();

        URI uri = resource.getURI();
        String uriString = uri.toString();

        info.fullPath = uriString;

        // Determine location type and extract info
        if (uriString.contains("jar!")) {
            info.locationType = "JAR Resource";

            // Extract JAR name
            String[] parts = uriString.split("!");
            if (parts.length > 0) {
                String jarPart = parts[0];
                int lastSlash = jarPart.lastIndexOf('/');
                if (lastSlash > 0) {
                    info.sourceJar = jarPart.substring(lastSlash + 1);
                }

                // Extract module name from JAR
                if (info.sourceJar.contains("plaintext-zeiterfassung")) {
                    info.module = "plaintext-zeiterfassung";
                } else if (info.sourceJar.contains("plaintext-webapp-menu")) {
                    info.module = "plaintext-webapp-menu";
                } else if (info.sourceJar.contains("plaintext-webapp")) {
                    info.module = "plaintext-webapp";
                } else if (info.sourceJar.contains("plaintext-legacy")) {
                    info.module = "plaintext-legacy";
                } else if (info.sourceJar.contains("primefaces")) {
                    info.module = "primefaces";
                } else {
                    info.module = "other";
                }
            }

            // Extract relative path from JAR
            if (parts.length > 1) {
                info.relativePath = parts[1];
                if (info.relativePath.startsWith("/")) {
                    info.relativePath = info.relativePath.substring(1);
                }
            }
        } else if (uriString.startsWith("file:")) {
            info.locationType = "File System";

            try {
                File file = resource.getFile();
                info.fileSize = file.length();

                // Extract relative path and module
                String absolutePath = file.getAbsolutePath();

                if (absolutePath.contains("/plaintext-zeiterfassung/")) {
                    info.module = "plaintext-zeiterfassung";
                    int idx = absolutePath.indexOf("/plaintext-zeiterfassung/");
                    info.relativePath = absolutePath.substring(idx + "/plaintext-zeiterfassung/".length());
                } else if (absolutePath.contains("/plaintext-webapp-menu/")) {
                    info.module = "plaintext-webapp-menu";
                    int idx = absolutePath.indexOf("/plaintext-webapp-menu/");
                    info.relativePath = absolutePath.substring(idx + "/plaintext-webapp-menu/".length());
                } else if (absolutePath.contains("/plaintext-webapp/")) {
                    info.module = "plaintext-webapp";
                    int idx = absolutePath.indexOf("/plaintext-webapp/");
                    info.relativePath = absolutePath.substring(idx + "/plaintext-webapp/".length());
                } else if (absolutePath.contains("/plaintext-legacy/")) {
                    info.module = "plaintext-legacy";
                    int idx = absolutePath.indexOf("/plaintext-legacy/");
                    info.relativePath = absolutePath.substring(idx + "/plaintext-legacy/".length());
                } else {
                    info.module = "unknown";
                    info.relativePath = file.getName();
                }
            } catch (Exception e) {
                info.relativePath = resource.getFilename();
                info.module = "unknown";
            }
        } else {
            info.locationType = "Other (" + uri.getScheme() + ")";
            info.relativePath = resource.getFilename();
            info.module = "unknown";
        }

        // Fallback for filename
        if (info.relativePath == null || info.relativePath.isEmpty()) {
            info.relativePath = resource.getFilename();
        }

        return info;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    /**
     * Scannt ein Verzeichnis rekursiv nach XHTML/HTML/JSF Dateien
     */
    private void scanDirectoryForResources(Path baseDir, Path currentDir, Map<String, List<ResourceInfo>> resourcesByLocation) throws IOException {
        if (!Files.exists(currentDir) || !Files.isDirectory(currentDir)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(currentDir)) {
            paths.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".jsf");
                })
                .forEach(filePath -> {
                    try {
                        ResourceInfo info = new ResourceInfo();
                        info.locationType = "File System (Source)";
                        info.fileSize = Files.size(filePath);
                        info.fullPath = filePath.toString();

                        // Extract relative path from base dir
                        Path relativePath = baseDir.relativize(filePath);
                        info.relativePath = relativePath.toString();

                        // Determine module from path
                        String pathStr = filePath.toString();
                        if (pathStr.contains("/plaintext-zeiterfassung/")) {
                            info.module = "plaintext-zeiterfassung";
                        } else if (pathStr.contains("/plaintext-webapp-menu/")) {
                            info.module = "plaintext-webapp-menu";
                        } else if (pathStr.contains("/plaintext-webapp/")) {
                            info.module = "plaintext-webapp";
                        } else if (pathStr.contains("/plaintext-legacy/")) {
                            info.module = "plaintext-legacy";
                        } else {
                            info.module = "unknown";
                        }

                        resourcesByLocation
                            .computeIfAbsent(info.locationType, k -> new ArrayList<>())
                            .add(info);

                    } catch (IOException e) {
                        log.warn("Error reading file {}: {}", filePath, e.getMessage());
                    }
                });
        }
    }

    private String htmlEscape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    private static class ResourceInfo {
        String relativePath = "";
        String fullPath = "";
        String locationType = "";
        String module = "";
        String sourceJar = "";
        long fileSize = 0;
    }
}
