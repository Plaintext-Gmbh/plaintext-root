/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.MenuRegistry;
import ch.plaintext.boot.menu.MenuAnnotation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.List;

@Controller
@Slf4j
public class MenuDebugController {

    @Autowired
    private MenuRegistry menuRegistry;

    @GetMapping("/debug/menu-scan")
    @ResponseBody
    public String debugMenuScan() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<title>Menu Debug Information</title>");
        html.append("<style>");
        html.append("body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }");
        html.append("h1 { color: #333; border-bottom: 3px solid #4CAF50; padding-bottom: 10px; }");
        html.append("h2 { color: #555; margin-top: 30px; }");
        html.append(".package-section { background: white; padding: 20px; margin: 20px 0; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }");
        html.append(".count { background: #4CAF50; color: white; padding: 5px 10px; border-radius: 4px; font-weight: bold; }");
        html.append(".class-list { list-style: none; padding: 0; }");
        html.append(".class-list li { padding: 8px; margin: 5px 0; background: #f9f9f9; border-left: 3px solid #2196F3; }");
        html.append(".bean-card { background: white; padding: 15px; margin: 10px 0; border-radius: 5px; border-left: 4px solid #FF9800; }");
        html.append(".bean-title { font-weight: bold; color: #333; font-size: 1.1em; }");
        html.append(".bean-detail { color: #666; margin: 5px 0; }");
        html.append(".label { font-weight: bold; color: #555; }");
        html.append(".summary { background: #E3F2FD; padding: 15px; border-radius: 5px; margin-bottom: 20px; }");
        html.append("</style>");
        html.append("</head><body>");

        html.append("<h1>🔍 Menu Annotation Debug Information</h1>");

        String[] packages = {"ch.plaintext"};
        int totalScanned = 0;

        for (String pkg : packages) {
            html.append("<div class='package-section'>");
            html.append("<h2>📦 Package: <code>").append(pkg).append("</code></h2>");

            ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(MenuAnnotation.class));

            List<String> found = new ArrayList<>();
            for (BeanDefinition beanDef : scanner.findCandidateComponents(pkg)) {
                found.add(beanDef.getBeanClassName());
            }

            totalScanned += found.size();
            html.append("<p>Found <span class='count'>").append(found.size()).append("</span> annotated classes</p>");
            html.append("<ul class='class-list'>");
            for (String className : found) {
                html.append("<li>").append(className).append("</li>");
            }
            html.append("</ul>");
            html.append("</div>");
        }

        // Check registered beans via MenuRegistry
        html.append("<div class='package-section'>");
        html.append("<h2>📋 Registered Menu Items (via MenuRegistry)</h2>");
        List<MenuRegistry.MenuItem> menuItems = menuRegistry.getAllMenuItems();

        html.append("<div class='summary'>");
        html.append("<strong>Total Scanned:</strong> ").append(totalScanned).append(" | ");
        html.append("<strong>Total Registered Beans:</strong> ").append(menuItems.size());
        if (totalScanned != menuItems.size()) {
            html.append(" <span style='color: red;'>⚠️ MISMATCH!</span>");
        } else {
            html.append(" <span style='color: green;'>✅ OK</span>");
        }
        html.append("</div>");

        for (MenuRegistry.MenuItem item : menuItems) {
            html.append("<div class='bean-card'>");
            html.append("<div class='bean-title'>").append(item.getFullTitle()).append("</div>");
            html.append("<div class='bean-detail'><span class='label'>Title:</span> ").append(item.getTitle()).append("</div>");
            html.append("<div class='bean-detail'><span class='label'>Link:</span> ").append(item.getLink()).append("</div>");
            html.append("<div class='bean-detail'><span class='label'>Parent:</span> ").append(item.getParent() == null || item.getParent().isEmpty() ? "<em>(root)</em>" : item.getParent()).append("</div>");
            html.append("<div class='bean-detail'><span class='label'>Order:</span> ").append(item.getOrder()).append("</div>");
            html.append("<div class='bean-detail'><span class='label'>Icon:</span> ").append(item.getIcon()).append("</div>");
            html.append("<div class='bean-detail'><span class='label'>Roles:</span> ").append(item.getRoles().isEmpty() ? "<em>(all users)</em>" : String.join(", ", item.getRoles())).append("</div>");
            html.append("</div>");
        }
        html.append("</div>");

        html.append("</body></html>");
        return html.toString();
    }
}
