/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.arch.modulapi;

import ch.plaintext.modules.ModulApi;
import ch.plaintext.modules.ModulApiUmsetzung;
import ch.plaintext.modules.ModulApiUmsetzung.Seiteneffekte;

/** Karte 1422: bekannte Fälle für {@code PlaintextModulApiVertragTest}. */
public final class Faelle {

    private Faelle() {
    }

    /** DTO ohne I-Präfix. */
    @ModulApi(art = ModulApi.Art.DTO)
    public interface Zeiteintrag {
        long minuten();
    }

    /** DTO mit I-Präfix. */
    @ModulApi(art = ModulApi.Art.DTO)
    public interface IZeiteintrag {
        long minuten();
    }

    /** Ein Dienst. */
    @ModulApi(art = ModulApi.Art.SCHNITTSTELLE)
    public interface IFotoQuelle {
        byte[] foto(long id);
    }

    /** Umsetzung ohne Beschreibung. */
    public static class OhneBeschreibung implements IFotoQuelle {
        @Override
        public byte[] foto(long id) {
            return new byte[0];
        }
    }

    /** Umsetzung mit Beschreibung, harmloser Text. */
    @ModulApiUmsetzung(beschreibung = "Liest vom Fotos-Sidecar.", seiteneffekte = Seiteneffekte.KEINE,
            hinweise = {"Nur eigener Mandant"}, beispiele = {"foto(7) -> JPEG"})
    public static class MitBeschreibung implements IFotoQuelle {
        @Override
        public byte[] foto(long id) {
            return new byte[0];
        }
    }

    /** Umsetzung eines DTO (Entity): braucht keine Beschreibung. */
    public static class Zeit implements IZeiteintrag {
        @Override
        public long minuten() {
            return 0;
        }
    }

    /** Ein Token in den Hinweisen. */
    @ModulApiUmsetzung(beschreibung = "Ruft die API.", seiteneffekte = Seiteneffekte.AUSSEN,
            hinweise = {"token = abc123"}, beispiele = {})
    public static class MitToken {
    }

    /** Eine interne Adresse in der Beschreibung. */
    @ModulApiUmsetzung(beschreibung = "Spricht mit 192.168.1.224:1883.", seiteneffekte = Seiteneffekte.AUSSEN,
            hinweise = {}, beispiele = {})
    public static class MitAdresse {
    }

    /** Werte-DTO als Record: ohne I-Präfix erlaubt. */
    @ModulApi(art = ModulApi.Art.DTO)
    public record Ortsangabe(double lat, double lon) {
    }

    /** Ein Erweiterungspunkt des Frameworks. */
    @ModulApi(art = ModulApi.Art.ERWEITERUNG)
    public interface Kachel {
        String titel();
    }

    /** Umsetzung eines Erweiterungspunkts: braucht keine Beschreibung. */
    public static class MeineKachel implements Kachel {
        @Override
        public String titel() {
            return "x";
        }
    }
}
