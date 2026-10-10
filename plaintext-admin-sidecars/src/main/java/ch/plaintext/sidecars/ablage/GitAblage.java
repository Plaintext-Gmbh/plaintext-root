/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.ablage;

import ch.plaintext.ablagen.AblageEintrag;
import ch.plaintext.ablagen.DateiAblage;
import ch.plaintext.modules.ModulApiUmsetzung;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * {@link DateiAblage} in einem Git-Repository (Karte 1471): Lesen aus dem Zweig, Schreiben und
 * Löschen als Commit mit dem angemeldeten Benutzer als Autor, danach Push.
 *
 * <p>Der lokale Arbeitsklon ist nur Zwischenspeicher: vor jeder Operation wird der Zweig geholt und
 * der Klon hart darauf gesetzt. Ein abgelehnter Push (jemand hat dazwischen gepusht) wird als
 * Konflikt gemeldet, nie mit Force überschrieben. Pfade wie bei {@link NextcloudAblage}; zusätzlich
 * ist {@code .git} als Segment verboten (sonst ließen sich Hooks oder die Konfiguration schreiben)
 * und Symlinks werden als Dateien ausgecheckt ({@code core.symlinks=false}), damit kein Pfad aus dem
 * Klon herausführt.</p>
 */
@ModulApiUmsetzung(beschreibung = "Stores, reads, lists and deletes files in a folder of a Git branch; every write is a commit by the signed-in user and a push.",
        seiteneffekte = ModulApiUmsetzung.Seiteneffekte.AUSSEN,
        hinweise = {"Paths stay below the folder, dot segments and .git are rejected", "A rejected push is reported as a conflict, never forced",
                "No redirects are followed", "The access token is stored encrypted", "Reads at most 50 MB per file"},
        beispiele = {"schreibe(\"diagramme/a.drawio\", bytes)"})
public final class GitAblage implements DateiAblage {

    private static final Map<Path, Object> SPERREN = new ConcurrentHashMap<>();
    private static final int ZEIT_SEKUNDEN = (int) NextcloudAblage.ZEIT.toSeconds();
    private static final String UNGUELTIG = "Ungültiger Pfad.";

    private final String name;
    private final String url;
    private final String zweig;
    private final String ordner;
    private final Path klon;
    private final CredentialsProvider zugang;
    private final Supplier<String> autor;
    /** Nur für den Konflikttest: läuft zwischen Commit und Push. */
    Runnable vorPush = () -> { };

    /**
     * @param url    Repo-Adresse (bereits geprüft), ohne Zugangsdaten
     * @param token  Zugangs-Token im Klartext (nur im Speicher) oder {@code null}
     * @param ordner Unterordner im Repo, leer = Wurzel
     * @param klon   Verzeichnis des lokalen Arbeitsklons dieser Ablage
     * @param autor  Login des angemeldeten Benutzers
     */
    public GitAblage(String name, String url, String zweig, String ordner, String benutzer, String token, Path klon,
                     Supplier<String> autor) throws IOException {
        this.name = name;
        this.url = url;
        this.zweig = zweig;
        this.ordner = normiere(ordner == null ? "" : ordner, true);
        this.klon = klon.toAbsolutePath().normalize();
        this.zugang = token == null ? null : new UsernamePasswordCredentialsProvider(benutzer == null ? "" : benutzer, token);
        this.autor = autor;
        if (!Repository.isValidRefName("refs/heads/" + zweig)) {
            throw new IOException("Ungültiger Zweig.");
        }
    }

    @Override
    public String name() {
        return name;
    }

    /** Prüft Pfadsegmente wie {@link NextcloudAblage#kodiere}; Ergebnis ohne führenden/abschliessenden Schrägstrich. */
    static String normiere(String pfad, boolean leerErlaubt) throws IOException {
        if (pfad == null || pfad.contains("\\") || pfad.chars().anyMatch(c -> c < 0x20)) {
            throw new IOException(UNGUELTIG);
        }
        String p = pfad.strip().replaceAll("^/+|/+$", "");
        if (p.isEmpty()) {
            if (leerErlaubt) {
                return "";
            }
            throw new IOException(UNGUELTIG);
        }
        for (String teil : p.split("/", -1)) {
            if (teil.isEmpty() || teil.equals("..") || teil.equals(".") || teil.toLowerCase(Locale.ROOT).equals(".git")) {
                throw new IOException(UNGUELTIG);
            }
        }
        return p;
    }

    /** Relativer Pfad in der Ablage → Pfad im Repo (mit Unterordner). */
    private String imRepo(String pfad, boolean leerErlaubt) throws IOException {
        if (pfad == null || (!leerErlaubt && pfad.startsWith("/"))) {
            throw new IOException(UNGUELTIG);
        }
        String p = normiere(pfad, leerErlaubt);
        return ordner.isEmpty() ? p : p.isEmpty() ? ordner : ordner + "/" + p;
    }

    private Path datei(String imRepo) throws IOException {
        Path d = klon.resolve(imRepo).normalize();
        if (!d.startsWith(klon)) {
            throw new IOException(UNGUELTIG);
        }
        return d;
    }

    @FunctionalInterface
    private interface Arbeit<T> {
        T mit(Git git) throws IOException, GitAPIException;
    }

    /** Holt den Zweig, setzt den Klon darauf und führt die Arbeit unter einer Sperre je Klon aus. */
    private <T> T arbeite(Arbeit<T> arbeit) throws IOException {
        synchronized (SPERREN.computeIfAbsent(klon, k -> new Object())) {
            Git git = null;
            try {
                git = oeffneKlon();
                if (!hole(git)) {
                    git.close();
                    loescheKlon();
                    git = oeffneKlon();
                    hole(git);
                }
                return arbeit.mit(git);
            } catch (GitAPIException | JGitInternalException e) {
                throw new IOException(meldung(e), e);
            } finally {
                if (git != null) {
                    git.close();
                }
            }
        }
    }

    private Git oeffneKlon() throws IOException, GitAPIException {
        if (Files.isDirectory(klon.resolve(".git"))) {
            return Git.open(klon.toFile());
        }
        Files.createDirectories(klon);
        Git git = Git.init().setDirectory(klon.toFile()).setInitialBranch(zweig).call();
        StoredConfig c = git.getRepository().getConfig();
        c.setString("remote", "origin", "url", url);
        c.setString("http", null, "followRedirects", "false");
        c.setBoolean("core", null, "symlinks", false);
        c.save();
        return git;
    }

    /** @return {@code false}, wenn der Klon einen nie gepushten Commit auf einem leeren Zweig trägt und neu angelegt werden muss */
    private boolean hole(Git git) throws IOException, GitAPIException {
        String entfernt = "refs/remotes/origin/" + zweig;
        Repository repo = git.getRepository();
        try {
            git.fetch().setRemote("origin").setRefSpecs("+refs/heads/" + zweig + ":" + entfernt)
                    .setCredentialsProvider(zugang).setTimeout(ZEIT_SEKUNDEN).call();
        } catch (TransportException e) {
            // Neues, leeres Repo bzw. neuer Zweig: entsteht beim ersten Push. Alles andere ist ein Fehler.
            if (e.getMessage() == null || !e.getMessage().contains("does not have refs/heads/" + zweig)) {
                throw e;
            }
            RefUpdate weg = repo.updateRef(entfernt);
            weg.setForceUpdate(true);
            weg.delete();
        }
        ObjectId stand = repo.resolve(entfernt);
        if (stand == null) {
            return repo.resolve("HEAD") == null;
        }
        RefUpdate u = repo.updateRef("refs/heads/" + zweig);
        u.setNewObjectId(stand);
        u.setForceUpdate(true);
        u.update();
        git.reset().setMode(ResetCommand.ResetType.HARD).call();
        git.clean().setCleanDirectories(true).setIgnore(false).call();
        return true;
    }

    private void loescheKlon() throws IOException {
        try (Stream<Path> s = Files.walk(klon)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Commit mit dem angemeldeten Benutzer als Autor, dann Push ohne Force; abgelehnt = Konflikt. */
    private void sichere(Git git, String nachricht) throws IOException, GitAPIException {
        String login = autor == null ? null : autor.get();
        String n = login == null || login.isBlank() ? "plaintext" : login.strip();
        PersonIdent wer = new PersonIdent(n, n.contains("@") ? n : "");
        git.commit().setAuthor(wer).setCommitter(wer).setMessage(nachricht).setSign(false).setNoVerify(true).call();
        vorPush.run();
        Iterable<PushResult> ergebnis = git.push().setRemote("origin").setRefSpecs(new RefSpec(
                "refs/heads/" + zweig + ":refs/heads/" + zweig)).setForce(false).setCredentialsProvider(zugang).setTimeout(ZEIT_SEKUNDEN).call();
        for (PushResult r : ergebnis) {
            for (RemoteRefUpdate u : r.getRemoteUpdates()) {
                switch (u.getStatus()) {
                    case OK, UP_TO_DATE -> { }
                    case REJECTED_NONFASTFORWARD, REJECTED_REMOTE_CHANGED, REJECTED_NODELETE ->
                            throw new IOException("Konflikt: der Zweig «" + zweig + "» wurde inzwischen geändert. "
                                    + "Nichts überschrieben; bitte neu laden und erneut speichern.");
                    default -> throw new IOException("Push abgelehnt (" + u.getStatus() + (u.getMessage() == null ? "" : ": " + u.getMessage()) + ").");
                }
            }
        }
    }

    static String meldung(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String k = m.toLowerCase(Locale.ROOT);
        if (k.contains("not authorized") || k.contains("authentication is required") || k.contains("401")) {
            return "Anmeldung abgelehnt (Benutzer oder Token falsch).";
        }
        if (k.contains("403") || k.contains("not permitted")) {
            return "Zugriff verweigert.";
        }
        if (k.contains("redirect")) {
            return "Der Server leitet weiter; bitte die Adresse direkt angeben.";
        }
        return "Git: " + m;
    }

    /** @return Meldung für die Oberfläche */
    public String pruefe() throws IOException {
        return arbeite(git -> {
            if (git.getRepository().resolve("HEAD") == null) {
                return "Verbindung in Ordnung, Zweig «" + zweig + "» ist noch leer.";
            }
            return "Verbindung in Ordnung, Zweig «" + zweig + "», " + liste(git, "").size() + " Einträge im Ordner.";
        });
    }

    @Override
    public void schreibe(String pfad, byte[] daten, String inhaltTyp) throws IOException {
        String r = imRepo(pfad, false);
        Path d = datei(r);
        arbeite(git -> {
            Files.createDirectories(d.getParent());
            Files.write(d, daten);
            git.add().addFilepattern(r).call();
            if (!git.status().call().isClean()) {
                sichere(git, "Ablage " + name + ": " + r + " gespeichert");
            }
            return null;
        });
    }

    @Override
    public byte[] lies(String pfad) throws IOException {
        Path d = datei(imRepo(pfad, false));
        return arbeite(git -> {
            if (!Files.isRegularFile(d)) {
                throw new IOException("Datei nicht gefunden.");
            }
            if (Files.size(d) > NextcloudAblage.MAX_LESEN) {
                throw new IOException("Datei grösser als 50 MB.");
            }
            return Files.readAllBytes(d);
        });
    }

    @Override
    public boolean existiert(String pfad) throws IOException {
        Path d = datei(imRepo(pfad, false));
        return arbeite(git -> Files.exists(d));
    }

    @Override
    public List<AblageEintrag> liste(String ordnerRel) throws IOException {
        String o = normiere(ordnerRel == null ? "" : ordnerRel, true);
        return arbeite(git -> liste(git, o));
    }

    /** Einträge relativ zur Ablage (wie NextcloudAblage); ein Ordner ohne Dateien gibt es in Git nicht = leer. */
    private List<AblageEintrag> liste(Git git, String o) throws IOException {
        Path basis = datei(imRepo("", true));
        Path d = datei(imRepo(o, true));
        List<AblageEintrag> l = new ArrayList<>();
        if (!Files.isDirectory(d)) {
            return l;
        }
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.sorted().toList()) {
                if (p.getFileName().toString().equals(".git")) {
                    continue;
                }
                boolean istOrdner = Files.isDirectory(p);
                l.add(new AblageEintrag(basis.relativize(p).toString().replace('\\', '/'), istOrdner, istOrdner ? -1 : Files.size(p), null));
            }
        }
        return l;
    }

    @Override
    public void loesche(String pfad) throws IOException {
        String r = imRepo(pfad, false);
        Path d = datei(r);
        arbeite(git -> {
            if (!Files.isRegularFile(d)) {
                throw new IOException("Datei nicht gefunden.");
            }
            git.rm().addFilepattern(r).call();
            sichere(git, "Ablage " + name + ": " + r + " gelöscht");
            return null;
        });
    }
}
