/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mailversand;

import ch.plaintext.arch.StabileApi;

import java.util.List;
import java.util.Optional;

/**
 * Auswahl von Absender-Mailkonten für den <b>automatischen</b> Versand — Cron, Scheduler,
 * Hintergrundjobs, Webhooks, {@code /nosec}-Flows: alles, was später <b>ohne angemeldeten
 * Benutzer</b> läuft.
 *
 * <p><b>Warum es diese Klasse gibt (Karte 1150, Befund 09.09.2026).</b> Die Geburtstags-Mail des
 * Mandanten {@code plaintext} blieb monatelang aus. Eingestellt war ein Mailkonto mit
 * {@code FreigabeScope.PERSOENLICH}. Die Einstellungsmaske hat es angeboten,
 * weil sie mit angemeldetem Benutzer läuft und {@code listAccounts(mandat, user)} dort auch die
 * eigenen persönlichen Konten liefert. Der Cron ruft dieselbe Methode mit {@code user == null};
 * {@code PERSOENLICH.erlaubt(…, null)} endet auf {@code aktuellerUser != null && …} und ist damit
 * <b>immer</b> {@code false}. Das Konto war für ihn unsichtbar, der Lauf brach täglich mit
 * „kein Mailkonto … eingestellt" ab — ohne dass die Oberfläche je gewarnt hätte.</p>
 *
 * <p><b>Die Regel.</b> Eine Auswahl, deren Ergebnis später <i>headless</i> benutzt wird, darf nur
 * anbieten, was headless auch auflösbar ist. Genau das ist {@code listAccounts(mandant, null)}:
 * derselbe Aufruf, den der Hintergrundjob später macht. Die Auswahlliste und der Versand fragen so
 * <b>dieselbe</b> Quelle mit <b>denselben</b> Argumenten — die zwei können nicht mehr
 * auseinanderlaufen.</p>
 *
 * <p><b>Ausblenden statt nur warnen.</b> Der Auftrag lautete „bei der Auswahl darauf hinweisen".
 * Ein blosser Hinweistext lässt den Fehlgriff weiterhin zu; er verschiebt nur, wann er auffällt —
 * und aufgefallen ist er hier erst nach Wochen Stille. Ein Konto, das der spätere Versand
 * nachweislich nicht benutzen kann, ist an dieser Stelle keine Wahl, sondern ein Ausfall mit
 * Vorlaufzeit. Es wird deshalb nicht angeboten; {@link #HINWEIS} sagt an der Maske, warum die
 * Liste kürzer ist als die unter „Mailbox", und {@link #WARNUNG_UNTAUGLICH} fängt Bestandswerte
 * ab, die noch auf ein solches Konto zeigen.</p>
 *
 * <p><b>Nicht</b> für Masken, deren Versand im Namen des angemeldeten Benutzers läuft (Mailbox,
 * Kalender-Einladungen von Hand, Rechnungs-Entwürfe): dort ist ein persönliches Konto die
 * richtige, gewollte Wahl und wird weiterhin angeboten.</p>
 *
 * <p><b>Karte 1152 (09.09.2026): Ausblenden war nicht genug.</b> Der Bestandsabgleich fand vier
 * Einstellungen, die auf ein <b>gelöschtes oder inaktives</b> Konto zeigten — dieselbe Stille wie
 * bei den Geburtstagen, andere Ursache. Ausblenden hilft dagegen nur beim <i>nächsten</i>
 * Speichern und nur, wenn jemand die Maske überhaupt öffnet: der bereits gespeicherte Wert bleibt
 * unangetastet stehen, und ein abgeschicktes Formular (Bestandswert, den die Maske unverändert
 * zurücksendet, oder ein von Hand gesetzter Parameter) trägt ihn wieder in die Datenbank. Genau so
 * überlebten die vier. Deshalb kommt zur gefilterten Liste jetzt {@link #ablehnung} — dieselbe
 * Regel, aber am Speicherweg: ein untauglicher Wert wird <b>nicht gespeichert</b>, und die Maske
 * sagt warum. Die Liste ist die Vorbeugung, die Ablehnung der Riegel.</p>
 *
 * <p><b>Karte 1162 (10.09.2026): der Schalter.</b> Ausblenden und Ablehnen beantworten die Frage
 * „taugt dieses Konto?". Sie beantworten nicht die Frage „will dieser Mandant die Funktion
 * überhaupt?" — und solange die niemand stellt, muss ein Hintergrundversand aus „nichts eingestellt"
 * raten. Der Geburtstags-Cron riet auf „gewollt" und warnte: am 10.09.2026 einmal berechtigt
 * (plaintext) und <b>neun Mal</b> für Mandanten, die die Funktion nie wollten. Ein Wächter, der
 * jeden Morgen neunmal ohne Anlass ruft, wird nach einer Woche überlesen — genau so blieb der echte
 * Ausfall in plaintext monatelang stehen (Karte 1150). Deshalb gehört zu jeder solchen Einstellung
 * ein <b>ausdrücklicher Schalter mit Vorgabe „aus"</b>; {@link #eingeschaltet} ist die eine Stelle,
 * an der diese Vorgabe definiert ist. Ist er aus, wird still übersprungen (DEBUG); ist er an und es
 * geht trotzdem nichts, <b>dann</b> ist die Warnung eine Nachricht und kein Lärm.</p>
 *
 * <p><b>Karte 1164 (10.09.2026): die Spiegelseite — der Wächter.</b> Wiki-Benachrichtigung,
 * Auszahlungs-Benachrichtigung und Serienevent-Versand hatten das umgekehrte Problem der
 * Geburtstage: sie waren <b>zu still</b>. Gemessen in PROD (09.09. 06:00 – 10.09. 08:00): 105 WARN
 * insgesamt, davon 12 vom Geburtstags-Cron und <b>null</b> mit Mail-Bezug — weil alle drei bei
 * fehlendem Konto wortlos aussteigen ({@code if (accountId == null) return;}). Damit hatten sie
 * faktisch einen Off-Default, aber sie hatten ihn, indem sie „nicht gewünscht" und „gewünscht und
 * kaputt" gar nicht unterscheiden konnten. Genau diese Verwechslung liess in Karte 1152 vier
 * Einstellungen auf gelöschte oder inaktive Konten zeigen, ohne dass irgendwo eine Zeile stand.
 * Deshalb bekommen alle drei denselben {@link #eingeschaltet}-Schalter — und dazu
 * {@link #versandMangel}, den Meldungstext für den einen Zustand, der ab jetzt gehört wird:
 * <b>eingeschaltet und trotzdem nicht versandfähig</b>.</p>
 *
 * <p><b>Warum nicht zwei Prüfungen.</b> {@link #ablehnung} benutzt {@link #waehlbar} und damit
 * {@link #waehlbareKonten} — es gibt weiterhin genau <b>eine</b> Quelle der Wahrheit, nämlich den
 * Aufruf, den der Hintergrundversand macht. Eine zweite, eigenständige Prüfung „ist das Konto
 * gelöscht oder inaktiv?" hätte danebengestanden und wäre beim nächsten Kriterium wieder
 * auseinandergelaufen.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@StabileApi("Auswahl der Absenderkonten fuer den automatischen Versand (Karte 1504, aus app-interfaces)")
public final class AutomatischerVersand {

    /** Hinweistext neben der Auswahl — erklärt, warum persönliche Konten fehlen. */
    public static final String HINWEIS =
            "Angeboten werden nur mandantenweit freigegebene Mailkonten. Dieser Versand läuft "
                    + "automatisch und damit ohne angemeldeten Benutzer — persönlich freigegebene "
                    + "Konten sind für ihn unsichtbar und würden den Versand stillschweigend "
                    + "verhindern. Ein persönliches Konto lässt sich in der Mailbox auf die "
                    + "Freigabe „Mandat“ umstellen, dann steht es hier zur Verfügung.";

    /** Meldung, wenn ein bereits gespeicherter Wert auf ein Konto zeigt, das automatisch nicht geht. */
    public static final String WARNUNG_UNTAUGLICH =
            "Das eingestellte Mailkonto ist für den automatischen Versand nicht nutzbar — es ist "
                    + "gelöscht, inaktiv oder nur persönlich freigegeben. Solange das so bleibt, wird "
                    + "nichts versendet. Bitte ein mandantenweit freigegebenes Konto wählen.";

    /**
     * Ablehnung für Masken, deren Versand im Namen eines Benutzers läuft (Serienevents, Karte
     * 1152). Dort ist ein persönliches Konto erlaubt — „nur persönlich freigegeben" wäre hier also
     * eine falsche Begründung; gelöscht oder inaktiv bleibt trotzdem untauglich.
     */
    public static final String WARNUNG_UNTAUGLICH_BENUTZER =
            "Das gewählte Mailkonto ist nicht nutzbar — es ist gelöscht, inaktiv oder für Sie nicht "
                    + "freigegeben. Solange das so bleibt, wird nichts versendet. Bitte ein anderes "
                    + "Konto wählen.";

    /** Hinweistext neben dem Schalter einer automatischen Benachrichtigung (Karte 1162). */
    public static final String SCHALTER_HINWEIS =
            "Ausgeschaltet läuft für diesen Mandanten nichts und es wird auch nichts gemeldet. "
                    + "Eingeschaltet versendet die Benachrichtigung automatisch — und meldet im "
                    + "Log, wenn sie es nicht kann. Neue Mandanten sind ausgeschaltet.";

    /**
     * Meldung der Maske, wenn eingeschaltet ist, aber kein Absender-Konto gewählt wurde — der eine
     * Zustand, der ab jetzt jeden Morgen eine Warnung ins Log schreibt (Karte 1162).
     */
    public static final String WARNUNG_EIN_OHNE_KONTO =
            "Eingeschaltet, aber kein Absender-Mailkonto gewählt: es wird nichts versendet, und der "
                    + "Hintergrundlauf meldet das ab morgen täglich als Warnung. Bitte ein Konto "
                    + "wählen — oder die Benachrichtigung ausschalten, wenn sie nicht gewünscht ist.";

    /**
     * Meldungsbaustein: es gibt gar keinen Versender (Mailbox-Modul fehlt oder ist nicht geladen).
     * Wortgleich zum Mangel, den der Geburtstags-Cron seit Karte 508 meldet — die drei Kanäle aus
     * Karte 1164 sollen im Log nicht anders klingen als der vierte.
     */
    public static final String MANGEL_KEIN_MAILSENDER = "MailSender nicht verfügbar";

    /** Meldungsbaustein: eingeschaltet, aber niemand hat einen Absender gewählt (Karte 1164). */
    public static final String MANGEL_KEIN_KONTO = "kein Absender-Mailkonto eingestellt";

    /**
     * Meldungsbaustein: es steht ein Absender da, aber der Versand kann ihn nicht auflösen — der
     * Fall aus Karte 1152 (gelöscht, inaktiv oder für diesen Versandweg nicht freigegeben). Die
     * Konto-Id gehört in die Meldung: ohne sie muss der Leser in der Datenbank suchen, welcher
     * Zeiger gemeint ist.
     */
    public static final String MANGEL_KONTO_UNTAUGLICH =
            "das eingestellte Absender-Mailkonto (Id %s) ist nicht nutzbar"
                    + " — gelöscht, inaktiv oder nicht freigegeben";

    private AutomatischerVersand() {
    }

    /**
     * <b>Die Off-Default-Regel an einer Stelle</b> (Karte 1162): Ist eine automatische
     * Benachrichtigung für diesen Mandanten eingeschaltet?
     *
     * <p>Nur ein ausdrückliches {@code true} zählt. {@code null} — Bestandsdaten aus der Zeit vor
     * dem Schalter, eine fehlende Einstellungszeile, eine nicht gesetzte Property — heisst
     * <b>aus</b>. Das ist die Vorgabe, die nicht rät: ein Mandant, von dem niemand etwas gesagt hat,
     * will keine automatische Rundmail und schon gar nicht eine tägliche Warnung darüber, dass sie
     * nicht funktioniert.</p>
     *
     * <p>Die Methode ist absichtlich winzig und trotzdem hier: sie ist der <b>eine</b> Ort, an dem
     * „aus, solange nicht ausdrücklich an" steht. Jede Einstellung, die denselben Schalter bekommt
     * (Geburtstage heute, Wiki/Auszahlungen/Serienevents, wenn sie ihn brauchen), fragt hier — sonst
     * läuft die Vorgabe je Modul auseinander, und wieder ist ein Kanal lauter als der Betreiber
     * wollte.</p>
     *
     * @param schalter der gespeicherte Wert; {@code null} gilt als aus
     * @return {@code true} nur bei ausdrücklichem {@code Boolean.TRUE}
     */
    public static boolean eingeschaltet(Boolean schalter) {
        return Boolean.TRUE.equals(schalter);
    }

    /**
     * <b>Der Wächter-Text an einer Stelle</b> (Karte 1164): Warum kann dieser <i>eingeschaltete</i>
     * Kanal nicht versenden? {@code Optional.empty()} heisst „versandfähig, es gibt nichts zu
     * melden".
     *
     * <p><b>Erst {@link #eingeschaltet} fragen, dann hier.</b> Diese Methode weiss nichts über den
     * Schalter und soll es nicht — sonst gäbe es zwei Orte, an denen die Off-Default-Vorgabe steht.
     * Der Aufrufer überspringt einen ausgeschalteten Mandanten still (DEBUG) und fragt erst danach,
     * ob der eingeschaltete auch kann.</p>
     *
     * <p><b>Der Unterschied zu {@link #ablehnung}, und warum es zwei Methoden sind.</b>
     * {@code ablehnung} bewertet einen <i>abgeschickten</i> Wert an der Maske; dort sind
     * „kein Konto" (= nicht versenden, eine gültige Entscheidung) und „kein Versender vorhanden"
     * (= nichts prüfbar) ausdrücklich <b>keine</b> Ablehnungsgründe. Hier ist die Lage umgekehrt:
     * Wer den Kanal eingeschaltet hat, hat gesagt „bitte versenden" — dann ist ein fehlendes Konto
     * kein Verzicht mehr, sondern ein Widerspruch, und ein fehlender Versender kein neutraler
     * Zustand, sondern der Grund, warum nichts ankommt. Beide Methoden fragen dieselbe Quelle
     * ({@link #waehlbareKonten} über {@link #waehlbar}); sie unterscheiden sich nur darin, was
     * „nichts eingestellt" in ihrem Zusammenhang bedeutet.</p>
     *
     * @param mailSender der zentrale Versender; {@code null} ist hier ein Mangel
     * @param mandant    Mandant der Einstellung
     * @param accountId  der eingestellte Absender; {@code null} ist hier ein Mangel
     * @return der Meldungstext für das Log, oder leer wenn versandfähig
     */
    public static Optional<String> versandMangel(MailSender mailSender, String mandant, Long accountId) {
        if (mailSender == null) {
            return Optional.of(MANGEL_KEIN_MAILSENDER);
        }
        if (accountId == null) {
            return Optional.of(MANGEL_KEIN_KONTO);
        }
        return waehlbar(mailSender, mandant, accountId)
                ? Optional.empty()
                : Optional.of(MANGEL_KONTO_UNTAUGLICH.formatted(accountId));
    }

    /**
     * Wie {@link #versandMangel(MailSender, String, Long)}, aber für einen Versand, der <b>im Namen
     * eines Benutzers</b> läuft — Serienevents senden absichtlich als {@code created_by} (Karte
     * 1150) und dürfen dessen persönliches Konto benutzen.
     *
     * <p>Ohne diese Variante wäre der Wächter ein Lügner: die einzige aktive Serie in PROD
     * (Mandant {@code plaintext}, „Jogging Dienstag") sendet über Konto 8
     * {@code Calsync (plaintext.ch)}, Scope {@code PERSOENLICH}. Gegen
     * {@link #waehlbareKonten} geprüft wäre sie „nicht versandfähig" — und die Karte hätte statt
     * eines Wächters eine tägliche Falschmeldung gebaut. Geprüft wird deshalb mit
     * {@link MailSender#accountEmailAddress} und demselben Benutzer, in dessen Namen der Versand
     * später läuft: derselbe Auflösungsweg, nur vorgezogen.</p>
     *
     * @param versandAls der Benutzer, in dessen Namen versendet wird; {@code null} verhält sich wie
     *                   {@link #versandMangel(MailSender, String, Long)}
     */
    public static Optional<String> versandMangel(MailSender mailSender, String mandant, String versandAls,
                                                 Long accountId) {
        if (versandAls == null) {
            return versandMangel(mailSender, mandant, accountId);
        }
        if (mailSender == null) {
            return Optional.of(MANGEL_KEIN_MAILSENDER);
        }
        if (accountId == null) {
            return Optional.of(MANGEL_KEIN_KONTO);
        }
        return mailSender.accountEmailAddress(accountId, mandant, versandAls) != null
                ? Optional.empty()
                : Optional.of(MANGEL_KONTO_UNTAUGLICH.formatted(accountId));
    }

    /**
     * Die Konten, die ein Hintergrundversand im {@code mandant} tatsächlich auflösen kann.
     *
     * <p>Bewusst {@code user = null}: das ist nicht eine Vereinfachung, sondern die Prüfung selbst —
     * derselbe Aufruf, den der Cron später macht.</p>
     *
     * @param mailSender der zentrale Versender; {@code null} (Mailbox-Modul fehlt) ergibt eine leere Liste
     * @param mandant    Mandant der Einstellung
     * @return die automatisch nutzbaren Konten, nie {@code null}
     */
    public static List<MailAccountRef> waehlbareKonten(MailSender mailSender, String mandant) {
        if (mailSender == null || mandant == null) {
            return List.of();
        }
        return mailSender.listAccounts(mandant, null);
    }

    /**
     * Ist {@code accountId} für den automatischen Versand nutzbar? Für die Prüfung eines bereits
     * gespeicherten Werts (Bestandsdaten aus der Zeit vor dieser Regel).
     *
     * @return {@code false} auch für {@code null} — „nichts eingestellt" ist keine nutzbare Wahl;
     *         die Aufrufer unterscheiden das selbst, weil „gar nicht versenden" oft gültig ist
     */
    public static boolean waehlbar(MailSender mailSender, String mandant, Long accountId) {
        return accountId != null
                && waehlbareKonten(mailSender, mandant).stream().anyMatch(k -> accountId.equals(k.id()));
    }

    /**
     * <b>Der Riegel am Speicherweg (Karte 1152).</b> Prüft einen abgeschickten Absender-Wert und
     * liefert den Ablehnungstext — {@code Optional.empty()} heisst „in Ordnung, speichern".
     *
     * <p>Für Einstellungen, deren Versand <b>ohne</b> angemeldeten Benutzer läuft. Ein Wert, der
     * hier abgelehnt wird, ist genau ein Wert, den der Hintergrundversand nicht auflösen kann:
     * gelöscht, inaktiv, oder nur persönlich freigegeben. Ihn zu speichern heisst, einen Ausfall
     * mit Vorlaufzeit einzurichten.</p>
     *
     * <p><b>{@code accountId == null} wird nicht abgelehnt.</b> „Kein Absender" ist eine gültige
     * Einstellung — sie bedeutet „nicht versenden" und ist genau dann richtig, wenn man das will.
     * Sie ist etwas anderes als ein Wert, der auf ein untaugliches Konto zeigt: der sieht aus wie
     * eine Zusage und hält sie nicht.</p>
     *
     * <p><b>Fehlt das Mailbox-Modul ({@code mailSender == null}), wird nicht abgelehnt.</b> Dann
     * lässt sich nichts prüfen, und es wird auch nichts versendet — eine Einstellungsseite
     * unbenutzbar zu machen, deren übrige Felder mit Mail nichts zu tun haben, wäre der falsche
     * Preis. Diese Lücke ist keine: ohne Versender kann kein Konto missbraucht werden.</p>
     *
     * @param mailSender der zentrale Versender; {@code null} (Mailbox-Modul fehlt) lehnt nie ab
     * @param mandant    Mandant der Einstellung
     * @param accountId  der abgeschickte Wert; {@code null} („kein Absender") wird nie abgelehnt
     * @return der Ablehnungstext für die Maske, oder leer wenn gespeichert werden darf
     */
    public static Optional<String> ablehnung(MailSender mailSender, String mandant, Long accountId) {
        if (accountId == null || mailSender == null) {
            return Optional.empty();
        }
        return waehlbar(mailSender, mandant, accountId)
                ? Optional.empty()
                : Optional.of(WARNUNG_UNTAUGLICH);
    }

    /**
     * Wie {@link #ablehnung(MailSender, String, Long)}, aber für einen Versand, der <b>im Namen
     * eines Benutzers</b> läuft — Serienevents senden absichtlich als {@code created_by} und
     * dürfen dessen persönliches Konto benutzen (Karte 1150).
     *
     * <p>Dort ist {@code PERSOENLICH} also kein Mangel; <b>gelöscht oder inaktiv</b> aber
     * weiterhin einer, und genau daran scheiterte
     * {@code serienevent_settings.default_absender_account_id}. Geprüft wird deshalb mit
     * {@link MailSender#accountEmailAddress} und dem Benutzer, in dessen Namen später versendet
     * wird: wieder <b>derselbe</b> Auflösungsweg wie im Versand, nur vorgezogen.</p>
     *
     * @param versandAls der Benutzer, in dessen Namen später versendet wird; {@code null}
     *                   verhält sich wie {@link #ablehnung(MailSender, String, Long)}
     */
    public static Optional<String> ablehnung(MailSender mailSender, String mandant, String versandAls,
                                             Long accountId) {
        if (versandAls == null) {
            return ablehnung(mailSender, mandant, accountId);
        }
        if (accountId == null || mailSender == null) {
            return Optional.empty();
        }
        return mailSender.accountEmailAddress(accountId, mandant, versandAls) != null
                ? Optional.empty()
                : Optional.of(WARNUNG_UNTAUGLICH_BENUTZER);
    }
}
