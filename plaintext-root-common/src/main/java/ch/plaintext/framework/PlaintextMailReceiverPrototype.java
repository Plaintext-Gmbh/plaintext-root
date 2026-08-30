/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;


import com.sun.mail.util.BASE64DecoderStream;
import jakarta.mail.*;

import jakarta.mail.internet.MimeMultipart;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;


import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Sends mails
 *
 * @author Plaintext GmbH
 * @since 1.2.8
 */
@Controller
@Scope("prototype")
@Slf4j
@Data
public class PlaintextMailReceiverPrototype implements PlaintextMailReceiver {

    Folder inbox;
    private String host;
    private String user;
    private String password;
    private Integer seenmails = 0;
    private Boolean connectionUp = Boolean.FALSE;

    @Override
    public List<PlaintextMailModel> checkMail(Boolean onlyNotSeen) {

        List<PlaintextMailModel> res = new ArrayList<>();

        Properties props = new Properties();
        props.setProperty("mail.imaps.port", "993");
        props.setProperty("mail.imaps.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        props.setProperty("mail.imaps.socketFactory.fallback", "false");
        props.setProperty("mail.imaps.connectiontimeout", "5000");
        props.setProperty("mail.imaps.timeout", "5000");

        try {

            if (inbox == null || !inbox.isOpen()) {
                connectionUp = false;
            }

            if (!connectionUp) {

                Session session = Session.getDefaultInstance(new Properties(), null);

                Store store = session.getStore("imaps");

                store.connect(host, user, password);

                inbox = store.getFolder("inbox");

                inbox.open(Folder.READ_WRITE);

                connectionUp = Boolean.TRUE;

            }

            Message[] messages = inbox.getMessages();

            for (Message message : messages) {

                seenmails++;

                if (message.getFlags().contains(Flags.Flag.SEEN) && onlyNotSeen) {
                    continue;
                }

                PlaintextMailModel mail = new PlaintextMailModel();

                Address[] from = message.getFrom();

                mail.setSender(from[0].toString());

                Address[] to = message.getAllRecipients();
                for (Address address : to) {
                    mail.addTo(address.toString());
                }

                mail.setSubject(message.getSubject());

                Multipart multipart = (Multipart) message.getContent();

                for (int j = 0; j < multipart.getCount(); j++) {

                    BodyPart bodyPart = multipart.getBodyPart(j);

                    if (bodyPart.getContent().toString().contains("javax.mail.internet.MimeMultipart")) {

                        MimeMultipart mp = (MimeMultipart) bodyPart.getContent();

                        for (int k = 0; k < mp.getCount(); k++) {
                            BodyPart bd = mp.getBodyPart(k);
                            String type2 = bd.getContentType();
                            String file = org.apache.commons.lang3.StringUtils.substringBetween(type2, "name=\"", "\";");
                            if (type2.contains("name")) {
                                byte[] bytes = IOUtils.toByteArray(bd.getInputStream());
                                mail.addAttachment(file, bytes);
                            }
                        }
                    } else if (bodyPart.getContent().toString().contains("com.sun.mail.util.BASE64DecoderStream")) {
                        BASE64DecoderStream base64DecoderStream = (BASE64DecoderStream) bodyPart.getContent();
                        byte[] bytes = IOUtils.toByteArray(base64DecoderStream);
                        mail.addAttachment("appointment.ics", bytes);

                    } else {
                        mail.setBody(bodyPart.getContent().toString());
                    }
                }

                if (onlyNotSeen) {
                    message.setFlag(Flags.Flag.SEEN, true);
                }

                res.add(mail);
            }

            connectionUp = Boolean.TRUE;

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            connectionUp = Boolean.FALSE;
        }

        return res;
    }

    @Override
    public Boolean connectionUp() {
        return connectionUp;
    }

}
