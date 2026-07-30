/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.MenuRegistry;
import ch.plaintext.boot.menu.MenuItemImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

@Controller
@Slf4j
public class MenuRegistryTestController {

    @Autowired(required = false)
    private MenuRegistry menuRegistry;

    @Autowired
    private ApplicationContext applicationContext;

    @GetMapping("/debug/menu-registry-test")
    @ResponseBody
    public String testMenuRegistry() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body><h1>Menu Registry Test</h1>");

        // Check if MenuRegistry is available
        sb.append("<h2>MenuRegistry Bean</h2>");
        if (menuRegistry != null) {
            sb.append("<p style='color: green;'>MenuRegistry is available: ").append(menuRegistry.getClass().getName()).append("</p>");

            List<String> titles = menuRegistry.getAllMenuTitles();
            sb.append("<p>getAllMenuTitles() returned ").append(titles.size()).append(" titles:</p>");
            sb.append("<ul>");
            for (String title : titles) {
                sb.append("<li>").append(title).append("</li>");
            }
            sb.append("</ul>");

            List<MenuRegistry.MenuItem> items = menuRegistry.getAllMenuItems();
            sb.append("<p>getAllMenuItems() returned ").append(items.size()).append(" items</p>");
        } else {
            sb.append("<p style='color: red;'>MenuRegistry is NULL</p>");
        }

        // Check MenuItemImpl beans directly
        sb.append("<h2>MenuItemImpl Beans (direct)</h2>");
        Map<String, MenuItemImpl> menuBeans = applicationContext.getBeansOfType(MenuItemImpl.class);
        sb.append("<p>Found ").append(menuBeans.size()).append(" MenuItemImpl beans:</p>");
        sb.append("<ul>");
        for (Map.Entry<String, MenuItemImpl> entry : menuBeans.entrySet()) {
            MenuItemImpl item = entry.getValue();
            sb.append("<li><b>").append(entry.getKey()).append("</b>: ");
            sb.append(item.getTitle()).append(" (parent: ").append(item.getParent()).append(", order: ").append(item.getOrder()).append(")");
            sb.append("</li>");
        }
        sb.append("</ul>");

        sb.append("</body></html>");
        return sb.toString();
    }
}
