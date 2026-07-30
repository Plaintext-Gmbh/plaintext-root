/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Optional;

@Controller
@Slf4j
public class MandateMenuDebugController {

    @Autowired(required = false)
    private MandateMenuConfigRepository repository;

    @GetMapping("/debug/mandate-menu-config")
    @ResponseBody
    public String debugMandateMenuConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body><h1>Mandate Menu Configuration Debug</h1>");

        if (repository == null) {
            sb.append("<p style='color: red;'>Repository is NULL</p>");
        } else {
            List<MandateMenuConfig> allConfigs = repository.findAll();
            sb.append("<h2>All Configurations</h2>");
            sb.append("<p>Found ").append(allConfigs.size()).append(" configurations</p>");

            for (MandateMenuConfig config : allConfigs) {
                sb.append("<h3>Mandate: ").append(config.getMandateName()).append("</h3>");
                sb.append("<p>Hidden Menus:</p><ul>");
                if (config.getHiddenMenus() != null && !config.getHiddenMenus().isEmpty()) {
                    for (String menu : config.getHiddenMenus()) {
                        sb.append("<li><code>").append(menu).append("</code></li>");
                    }
                } else {
                    sb.append("<li><em>No hidden menus</em></li>");
                }
                sb.append("</ul>");
            }

            // Test specific mandate
            sb.append("<h2>Test 'default' Mandate</h2>");
            Optional<MandateMenuConfig> defaultConfig = repository.findByMandateName("default");
            if (defaultConfig.isPresent()) {
                MandateMenuConfig config = defaultConfig.get();
                sb.append("<p>Found config for 'default'</p>");
                sb.append("<p>Hidden menus:</p><ul>");
                for (String menu : config.getHiddenMenus()) {
                    sb.append("<li><code>").append(menu).append("</code></li>");
                }
                sb.append("</ul>");

                // Test visibility
                sb.append("<h3>Visibility Tests</h3>");
                String[] testMenus = {"Kontakt", "Kontakte", "Admin", "Root"};
                for (String testMenu : testMenus) {
                    boolean isHidden = config.isMenuHidden(testMenu);
                    sb.append("<p><code>").append(testMenu).append("</code>: ")
                       .append(isHidden ? "<span style='color: red;'>HIDDEN</span>" : "<span style='color: green;'>VISIBLE</span>")
                       .append("</p>");
                }
            } else {
                sb.append("<p style='color: orange;'>No config found for 'default'</p>");
            }
        }

        sb.append("</body></html>");
        return sb.toString();
    }
}
