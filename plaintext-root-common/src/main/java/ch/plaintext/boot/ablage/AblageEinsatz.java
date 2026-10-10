/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.ablage;

import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Was ein Einsatzort von {@link AblageAuswahl} erlaubt (Karte 1440): welche Ablagen für welche Rollen,
 * unter welchem Ordner, welche Dateitypen und wie gross.
 *
 * @param freigaben  je Ablage die Rollen zum Lesen und Schreiben; {@value #ALLE} als Name gilt für jede
 *                   eingerichtete Ablage ohne eigene Freigabe. Leere Rollenmenge = niemand.
 * @param wurzel     Ordner in der Ablage, über den die Oberfläche nicht hinauskommt; leer = Ablage-Wurzel.
 *                   Den Mandats-Unterordner setzt der Einsatzort hier selbst (Ablagen sind instanzweit).
 * @param dateitypen erlaubte Endungen ohne Punkt, z.B. {@code drawio}; leer = alle (nur ohne Schreibrecht erlaubt)
 * @param maxBytes   Grössengrenze für Lesen, Herunterladen, Hochladen und Speichern; höchstens {@value #MAX_BYTES}
 */
public record AblageEinsatz(List<Freigabe> freigaben, String wurzel, Set<String> dateitypen, long maxBytes)
        implements Serializable {

    /** Freigabe für alle eingerichteten Ablagen. */
    public static final String ALLE = "*";

    /** Grenze der Ablagen selbst ({@code NextcloudAblage} liest höchstens 50 MB). */
    public static final long MAX_BYTES = 50L * 1024 * 1024;

    /** Rollen ohne {@code ROLE_}-Präfix, wie in {@code @MenuAnnotation}. */
    public record Freigabe(String ablage, Set<String> lesen, Set<String> schreiben) implements Serializable {
        public Freigabe {
            lesen = Set.copyOf(lesen);
            schreiben = Set.copyOf(schreiben);
        }
    }

    public AblageEinsatz {
        freigaben = List.copyOf(freigaben);
        wurzel = AblageAuswahl.pruefePfad(wurzel == null ? "" : wurzel.strip());
        dateitypen = dateitypen.stream().map(t -> t.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (dateitypen.stream().anyMatch(t -> !t.matches("[a-z0-9]{1,15}"))) {
            throw new IllegalArgumentException("Dateitypen: Endungen aus a-z und 0-9, ohne Punkt.");
        }
        if (maxBytes <= 0 || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("maxBytes muss zwischen 1 und " + MAX_BYTES + " liegen.");
        }
        if (dateitypen.isEmpty() && freigaben.stream().anyMatch(f -> !f.schreiben().isEmpty())) {
            throw new IllegalArgumentException("Wer Schreiben erlaubt, muss die Dateitypen nennen.");
        }
    }
}
