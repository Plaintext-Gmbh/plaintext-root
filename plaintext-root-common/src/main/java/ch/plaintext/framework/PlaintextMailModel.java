/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import lombok.Data;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Backup copy of a mail
 *
 * @author Plaintext GmbH
 * @since 0.0.1
 */
@Data
@ToString
public class PlaintextMailModel {

    private String mandat = "default";
    private String sender = "";
    private Set<String> receiver = new HashSet<>();
    private Set<String> receiverCC = new HashSet<>();
    private Set<String> receiverBCC = new HashSet<>();
    private String subject;
    private String body;
    private List<PlaintextEmailAttachment> attachments = new ArrayList<>();
    private Boolean html = Boolean.FALSE;

    public void addTo(String to) {
        if (StringUtils.isEmpty(to)) {
            return;
        }
        if (to.contains(",")) {
            String[] arr = to.split(",");
            for (String t : arr) {
                receiver.add(t);
            }
        } else {
            receiver.add(to);
        }
    }

    public void addCC(String cc) {
        if (StringUtils.isEmpty(cc)) {
            return;
        }
        if (cc.contains(",")) {
            String[] arr = cc.split(",");
            for (String c : arr) {
                receiverCC.add(c);
            }
        } else {
            receiverCC.add(cc);
        }
    }

    public void addBCC(String bcc) {
        if (StringUtils.isEmpty(bcc)) {
            return;
        }
        if (bcc.contains(",")) {
            String[] arr = bcc.split(",");
            for (String c : arr) {
                receiverBCC.add(c);
            }
        } else {
            receiverBCC.add(bcc);
        }
    }

    public void addAttachment(String name, byte[] content) {
        PlaintextEmailAttachment at = new PlaintextEmailAttachment();
        at.setAttachement(content);
        at.setName(name);
        attachments.add(at);
    }

    @Deprecated
    public void addReceiver(String name) {
        receiver.add(name);
    }

}
