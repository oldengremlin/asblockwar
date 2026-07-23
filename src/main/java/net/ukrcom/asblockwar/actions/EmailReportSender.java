/*
 * Copyright 2026 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ukrcom.asblockwar.actions;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.ukrcom.asblockwar.ASBlockWar;
import net.ukrcom.asblockwar.serviceStructures.Action;
import net.ukrcom.asblockwar.serviceStructures.ASN;
import net.ukrcom.asblockwar.serviceStructures.SuspiciousAS;

/**
 * Формує та відправляє HTML-звіт про результати обробки ASBlockWar.
 * <p>
 * Відправлення відбувається через {@code /usr/sbin/sendmail -t} (якщо SMTP-хост не вказано)
 * або через SMTP-з'єднання (якщо {@code email.smtp.host} налаштовано).
 * Виклик відбувається після {@link net.ukrcom.asblockwar.actions.Reporter#report} у
 * {@code ASBlockWar.runProcessing()}.
 *
 * @author olden
 */
@Slf4j
public class EmailReportSender {

    private EmailReportSender() {
    }

    /**
     * Відправляє HTML-звіт, якщо email.from і email.to налаштовано.
     * У режимі dry-run у темі листа з'являється позначка "[DRY RUN]".
     *
     * @param aggressorAsnResources фінальна карта {@code ASN → RPSL-блок}
     */
    public static void sendIfEnabled(Map<String, String> aggressorAsnResources) {
        String emailFrom = ASBlockWar.config.getEmailFrom();
        String emailTo   = ASBlockWar.config.getEmailTo();

        if (emailFrom == null || emailFrom.isBlank()) {
            log.warn("EmailReport: email.from не налаштовано, відправлення скасовано");
            return;
        }
        if (emailTo == null || emailTo.isBlank()) {
            log.warn("EmailReport: email.to не налаштовано, відправлення скасовано");
            return;
        }

        try {
            String subject = buildSubject();
            String html    = buildHtml(aggressorAsnResources);
            send(subject, html);
            log.info("EmailReport: звіт відправлено → {}", emailTo);
        } catch (Exception e) {
            log.error("EmailReport: помилка відправлення: {}", e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Subject
    // -----------------------------------------------------------------------

    private static String buildSubject() {
        String ts    = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String dry   = ASBlockWar.config.isDryRun() ? " [DRY RUN]" : "";
        long added   = count(Action.add);
        long removed = count(Action.remove);
        long modified= count(Action.modify);
        long susp    = ASBlockWar.suspiciousAsnResources.size();
        BlackbgpChanges bgp = ASBlockWar.lastBlackbgpChanges;
        int bgpDel   = bgp != null ? bgp.toDelete().size()  : 0;
        int bgpAdd   = bgp != null ? bgp.toReplace().size() : 0;

        return String.format("ASBlockWar%s: %s — AS +%d -%d ~%d | susp %d | bgp -%d +%d",
                dry, ts, added, removed, modified, susp, bgpDel, bgpAdd);
    }

    // -----------------------------------------------------------------------
    // HTML
    // -----------------------------------------------------------------------

    private static String buildHtml(Map<String, String> aggressorAsnResources) {
        String ts      = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String dryBanner = ASBlockWar.config.isDryRun()
                ? "<div style=\"background:#fff3cd;border:1px solid #ffc107;padding:8px 12px;"
                  + "margin-bottom:16px;border-radius:4px;\">"
                  + "&#9888; <b>DRY RUN — файли не записувались, фактичних змін не внесено</b></div>"
                : "";

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>"
                + CSS + "</style></head><body>"
                + "<h1>ASBlockWar &#8212; Run Report</h1>"
                + "<p style=\"color:#666;margin-top:-8px;\">" + esc(ts)
                + " &nbsp;|&nbsp; Всього заблоковано: <b>"
                + aggressorAsnResources.size() + "</b> ASN</p>"
                + dryBanner
                + buildAsnSection()
                + buildSuspiciousSection()
                + buildRouteSection(false, aggressorAsnResources)
                + buildRouteSection(true,  aggressorAsnResources)
                + "<div class=\"footer\">Згенеровано ASBlockWar</div>"
                + "</body></html>";
    }

    /** Зведена таблиця змін ASN (вилучені / додані / змінені). */
    private static String buildAsnSection() {
        Comparator<ASN> byAsn = Comparator.comparingLong(a -> Long.parseLong(a.asn().substring(2)));
        List<ASN> removed  = filterAsn(Action.remove, byAsn);
        List<ASN> added    = filterAsn(Action.add,    byAsn);
        List<ASN> modified = filterAsn(Action.modify,  byAsn);

        String badge = badge("-" + removed.size(),  "badge-del") + " "
                     + badge("+" + added.size(),   "badge-add") + " "
                     + badge("~" + modified.size(),"badge-mod");
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"section\"><h2>Зміни ASN ").append(badge).append("</h2>");

        if (removed.isEmpty() && added.isEmpty() && modified.isEmpty()) {
            sb.append("<p class=\"no-changes\">Змін ASN не виявлено</p></div>");
            return sb.toString();
        }

        sb.append("<table cellspacing=\"0\" cellpadding=\"5\" border=\"1\" class=\"shadow-table\">"
                + "<thead><tr>"
                + "<th valign=\"top\">ASN</th>"
                + "<th valign=\"top\">&#1044;&#1110;&#1103;</th>"
                + "<th valign=\"top\">Country</th>"
                + "<th valign=\"top\">&#1054;&#1088;&#1075;&#1072;&#1085;&#1110;&#1079;&#1072;&#1094;&#1110;&#1103;</th>"
                + "</tr></thead><tbody>");

        List<ASN> all = new ArrayList<>(removed);
        all.addAll(added);
        all.addAll(modified);
        all.sort(byAsn);

        for (ASN a : all) {
            String rowCls = switch (a.action()) {
                case add    -> "row-add";
                case remove -> "row-del";
                case modify -> "row-mod";
            };
            String actHtml = switch (a.action()) {
                case add    -> "<span class=\"action-add\">&#1044;&#1086;&#1076;&#1072;&#1085;&#1086;</span>";
                case remove -> "<span class=\"action-del\">&#1042;&#1080;&#1083;&#1091;&#1095;&#1077;&#1085;&#1086;</span>";
                case modify -> "<span class=\"action-mod\">&#1047;&#1084;&#1110;&#1085;&#1077;&#1085;&#1086;</span>";
            };
            String country = esc(RpslUtils.rpslField(a.data(), "country"));
            String org     = esc(RpslUtils.rpslField(a.data(), "org-name"));
            sb.append("<tr class=\"").append(rowCls).append("\">")
              .append("<td valign=\"top\"><span class=\"asn\">").append(asnHtml(a.asn())).append("</span></td>")
              .append("<td valign=\"top\">").append(actHtml).append("</td>")
              .append("<td valign=\"top\">").append(country).append("</td>")
              .append("<td valign=\"top\">").append(org).append("</td>")
              .append("</tr>");
        }

        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    /** Таблиця підозрілих AS (збіг з AggressorPattern, але не в BlockCountry). */
    private static String buildSuspiciousSection() {
        List<SuspiciousAS> list = ASBlockWar.suspiciousAsnResources.values().stream()
                .sorted(Comparator.comparingLong(s -> Long.parseLong(s.asn().substring(2))))
                .collect(Collectors.toList());

        String badge = badge(String.valueOf(list.size()), "badge-sus");
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"section\"><h2>")
          .append("&#1055;&#1110;&#1076;&#1086;&#1079;&#1088;&#1110;&#1083;&#1110; AS &#1087;&#1086;&#1079;&#1072; BlockCountry ")
          .append(badge).append("</h2>");

        if (list.isEmpty()) {
            sb.append("<p class=\"no-changes\">&#1055;&#1110;&#1076;&#1086;&#1079;&#1088;&#1110;&#1083;&#1080;&#1093; AS &#1085;&#1077; &#1074;&#1080;&#1103;&#1074;&#1083;&#1077;&#1085;&#1086;</p></div>");
            return sb.toString();
        }

        sb.append("<table cellspacing=\"0\" cellpadding=\"5\" border=\"1\" class=\"shadow-table\">"
                + "<thead><tr>"
                + "<th valign=\"top\">ASN</th>"
                + "<th valign=\"top\">Country</th>"
                + "<th valign=\"top\">&#1047;&#1073;&#1110;&#1075; &#1079; AggressorPattern</th>"
                + "</tr></thead><tbody>");

        boolean even = false;
        for (SuspiciousAS s : list) {
            sb.append(even ? "<tr class=\"row-even\">" : "<tr>")
              .append("<td valign=\"top\"><span class=\"asn action-sus\">").append(asnHtml(s.asn())).append("</span></td>")
              .append("<td valign=\"top\">").append(esc(s.country())).append("</td>")
              .append("<td valign=\"top\"><code>").append(esc(s.matchedLine())).append("</code></td>")
              .append("</tr>");
            even = !even;
        }

        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    /**
     * Таблиця змін маршрутів blackbgp.
     *
     * @param forReplace {@code true} = ip r r (додані/оновлені); {@code false} = ip r d (видалені)
     * @param aggressorAsnResources для пошуку опису ASN
     */
    private static String buildRouteSection(boolean forReplace, Map<String, String> aggressorAsnResources) {
        BlackbgpChanges bgp = ASBlockWar.lastBlackbgpChanges;
        if (bgp == null) {
            return "";
        }

        java.util.Set<String> prefixes = forReplace ? bgp.toReplace() : bgp.toDelete();

        String title = forReplace
                ? "&#1052;&#1072;&#1088;&#1096;&#1088;&#1091;&#1090;&#1080; &#8212; &#1076;&#1086;&#1076;&#1072;&#1085;&#1086;/&#1086;&#1085;&#1086;&#1074;&#1083;&#1077;&#1085;&#1086; &#1091; blackbgp (ip r r)"
                : "&#1052;&#1072;&#1088;&#1096;&#1088;&#1091;&#1090;&#1080; &#8212; &#1074;&#1080;&#1076;&#1072;&#1083;&#1077;&#1085;&#1086; &#1079; blackbgp (ip r d)";
        String badgeCls = forReplace ? "badge-bgp-r" : "badge-bgp-d";
        String rowCls   = forReplace ? "row-bgp-add" : "row-bgp-del";

        String badge = badge(String.valueOf(prefixes.size()), badgeCls);
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"section\"><h2>").append(title).append(" ").append(badge).append("</h2>");

        if (prefixes.isEmpty()) {
            sb.append("<p class=\"no-changes\">&#1047;&#1084;&#1110;&#1085; &#1084;&#1072;&#1088;&#1096;&#1088;&#1091;&#1090;&#1110;&#1074; &#1085;&#1077; &#1074;&#1080;&#1103;&#1074;&#1083;&#1077;&#1085;&#1086;</p></div>");
            return sb.toString();
        }

        List<String> sorted = prefixes.stream()
                .sorted(NetworkUtils.NETWORK_ADDR_ORDER)
                .collect(Collectors.toList());

        sb.append("<table cellspacing=\"0\" cellpadding=\"5\" border=\"1\" class=\"shadow-table\">"
                + "<thead><tr>"
                + "<th valign=\"top\">IPv4/IPv6</th>"
                + "<th valign=\"top\">Origin</th>"
                + "<th valign=\"top\">Country</th>"
                + "<th valign=\"top\">Descr</th>"
                + "</tr></thead><tbody>");

        Map<String, List<String>> origins = ASBlockWar.lastRouteOrigins;

        for (String prefix : sorted) {
            List<String> asnList = origins != null
                    ? origins.getOrDefault(prefix, Collections.emptyList())
                    : Collections.emptyList();

            if (asnList.isEmpty()) {
                sb.append("<tr class=\"").append(rowCls).append("\">")
                  .append("<td valign=\"top\">").append(esc(prefix)).append("</td>")
                  .append("<td valign=\"top\"></td>")
                  .append("<td valign=\"top\"></td>")
                  .append("<td valign=\"top\"></td>")
                  .append("</tr>");
                continue;
            }

            boolean firstRow = true;
            for (String asn : asnList) {
                String rpsl    = lookupRpsl(asn, aggressorAsnResources);
                String country = esc(RpslUtils.rpslField(rpsl, "country"));
                String descr   = esc(RpslUtils.rpslField(rpsl, "org-name"));
                if (descr.isEmpty()) {
                    descr = esc(RpslUtils.rpslField(rpsl, "descr"));
                }
                sb.append("<tr class=\"").append(rowCls).append("\">");
                sb.append(firstRow
                        ? "<td valign=\"top\">" + esc(prefix) + "</td>"
                        : "<td valign=\"top\"></td>");
                sb.append("<td valign=\"top\"><span class=\"asn\">").append(asnHtml(asn)).append("</span></td>")
                  .append("<td valign=\"top\">").append(country).append("</td>")
                  .append("<td valign=\"top\">").append(descr).append("</td>")
                  .append("</tr>");
                firstRow = false;
            }
        }

        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Send
    // -----------------------------------------------------------------------

    private static void send(String subject, String html)
            throws IOException, MessagingException, InterruptedException {

        String smtpHost = ASBlockWar.config.getEmailSmtpHost();
        boolean useSendmail = smtpHost == null || smtpHost.isBlank();

        Session session;
        if (useSendmail) {
            session = Session.getInstance(new Properties());
        } else {
            String smtpPort = ASBlockWar.config.getEmailSmtpPort();
            String smtpUser = ASBlockWar.config.getEmailSmtpUser();
            String smtpPass = ASBlockWar.config.getEmailSmtpPassword();
            String portStr  = smtpPort != null && !smtpPort.isBlank() ? smtpPort : "25";
            int    portNum  = 25;
            try { portNum = Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}

            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", portStr);

            boolean useAuth = smtpUser != null && !smtpUser.isBlank();
            props.put("mail.smtp.auth", String.valueOf(useAuth));

            String sslTrust = ASBlockWar.config.getEmailSmtpSslTrust();
            if (portNum == 465) {
                // SMTPS — одразу SSL/TLS
                props.put("mail.smtp.ssl.enable", "true");
                if (sslTrust != null && !sslTrust.isBlank()) {
                    props.put("mail.smtp.ssl.trust", sslTrust);
                }
            } else if (portNum == 587 || portNum == 2587) {
                // STARTTLS (submission)
                props.put("mail.smtp.starttls.enable",   "true");
                props.put("mail.smtp.starttls.required", "true");
                if (sslTrust != null && !sslTrust.isBlank()) {
                    props.put("mail.smtp.ssl.trust", sslTrust);
                }
            }

            if (useAuth) {
                final String u = smtpUser;
                final String p = smtpPass != null ? smtpPass : "";
                session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(u, p);
                    }
                });
            } else {
                session = Session.getInstance(props);
            }
        }

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(ASBlockWar.config.getEmailFrom()));

        String replyTo = ASBlockWar.config.getEmailReplyTo();
        if (replyTo != null && !replyTo.isBlank()) {
            message.setReplyTo(new InternetAddress[]{new InternetAddress(replyTo)});
        }

        for (String addr : ASBlockWar.config.getEmailTo().split(",")) {
            addr = addr.trim();
            if (!addr.isEmpty()) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(addr));
            }
        }

        message.setSubject(subject, "UTF-8");
        message.setHeader("X-Mailer", "ASBlockWar");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(html, "text/html; charset=UTF-8");
        htmlPart.setHeader("Content-Transfer-Encoding", "base64");

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(htmlPart);
        message.setContent(multipart);

        if (useSendmail) {
            try (PipedInputStream in = new PipedInputStream();
                 PipedOutputStream pipeOut = new PipedOutputStream(in)) {

                Thread writer = Thread.ofVirtual().start(() -> {
                    try {
                        message.writeTo(pipeOut);
                    } catch (MessagingException | IOException e) {
                        log.error("EmailReport: помилка серіалізації MIME: {}", e.getMessage());
                    } finally {
                        try { pipeOut.close(); } catch (IOException ignored) {}
                    }
                });

                ProcessBuilder pb = new ProcessBuilder("/usr/sbin/sendmail", "-t");
                Process proc = pb.start();
                try (OutputStream procOut = proc.getOutputStream()) {
                    in.transferTo(procOut);
                }
                int code = proc.waitFor();
                writer.join();
                if (code != 0) {
                    log.error("EmailReport: sendmail завершився з кодом {}", code);
                }
            }
        } else {
            Transport.send(message);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static long count(Action action) {
        return ASBlockWar.resourcesForVerification.values().stream()
                .filter(a -> a.action() == action).count();
    }

    private static List<ASN> filterAsn(Action action, Comparator<ASN> cmp) {
        return ASBlockWar.resourcesForVerification.values().stream()
                .filter(a -> a.action() == action)
                .sorted(cmp)
                .collect(Collectors.toList());
    }

    /** Шукає RPSL для ASN: спочатку в поточній карті ворогів, потім у resourcesForVerification (для видалених). */
    private static String lookupRpsl(String asn, Map<String, String> aggressorAsnResources) {
        String rpsl = aggressorAsnResources.get(asn);
        if (rpsl != null && !rpsl.isBlank()) {
            return rpsl;
        }
        ASN entry = ASBlockWar.resourcesForVerification.get(asn);
        if (entry != null && entry.data() != null && !entry.data().isBlank()) {
            return entry.data();
        }
        return "";
    }

    /** Форматує ASN як HTML: {@code AS<b>12345</b>}. */
    private static String asnHtml(String asn) {
        if (asn == null) {
            return "";
        }
        String upper = asn.toUpperCase();
        if (!upper.startsWith("AS")) {
            return esc(asn);
        }
        return "AS<b>" + esc(upper.substring(2)) + "</b>";
    }

    private static String badge(String text, String cls) {
        return "<span class=\"badge " + cls + "\">" + esc(text) + "</span>";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // -----------------------------------------------------------------------
    // CSS
    // -----------------------------------------------------------------------

    private static final String CSS
            = "body{font-family:Arial,sans-serif;font-size:13px;background:#f0f2f5;color:#222;margin:0;padding:16px}"
            + "h1{font-size:18px;color:#1a1a2e;margin-bottom:4px}"
            + "h2{font-size:13px;color:#16213e;margin:24px 0 6px;background:#e8eaf0;padding:5px 10px;border-left:4px solid #37474f}"
            + ".section{margin-bottom:20px}"
            + ".badge{display:inline-block;font-size:11px;color:#fff;padding:2px 7px;border-radius:10px;margin-left:4px}"
            + ".badge-add{background:#2e7d32}"
            + ".badge-del{background:#c62828}"
            + ".badge-mod{background:#b45309}"
            + ".badge-sus{background:#7b1fa2}"
            + ".badge-bgp-d{background:#bf360c}"
            + ".badge-bgp-r{background:#1565c0}"
            + ".shadow-table{border-collapse:collapse;width:100%;background:#fff;box-shadow:2px 2px 6px rgba(0,0,0,.2)}"
            + "th{background:#37474f;color:#fff;padding:6px 10px;text-align:left;font-size:12px;border:1px solid #546e7a}"
            + "td{padding:5px 10px;border:1px solid #cfd8dc;vertical-align:top;font-size:12px}"
            + ".row-even td{background:#f5f7f8}"
            + ".row-add td{background:#f0fff4}"
            + ".row-del td{background:#fff8f8}"
            + ".row-mod td{background:#fffdf0}"
            + ".row-bgp-add td{background:#f0fff4}"
            + ".row-bgp-del td{background:#fff8f8}"
            + ".no-changes{color:#888;font-style:italic;padding:4px 0}"
            + ".asn{font-family:monospace}"
            + ".action-add{color:#2e7d32;font-weight:bold}"
            + ".action-del{color:#c62828;font-weight:bold}"
            + ".action-mod{color:#e65100;font-weight:bold}"
            + ".action-sus{color:#6a1b9a;font-weight:bold}"
            + ".footer{font-size:11px;color:#aaa;margin-top:24px;border-top:1px solid #ddd;padding-top:8px}"
            + "code{font-family:monospace;font-size:11px;background:#f5f5f5;padding:1px 3px}";
}
