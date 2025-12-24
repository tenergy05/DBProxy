package com.poc.pamport.dbproxy.cassandra;

import java.util.HashMap;
import java.util.Map;
import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

/**
 * Minimal GSS/Kerberos client for Cassandra SASL.
 */
final class CassandraGssAuthenticator {
    private static final String KRB5_LOGIN = "com.sun.security.auth.module.Krb5LoginModule";
    private static final Oid KRB5_OID;

    static {
        try {
            KRB5_OID = new Oid("1.2.840.113554.1.2.2");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final String host;
    private final String servicePrincipal;
    private final String krb5ConfPath;
    private final String krb5CcName;
    private final String clientPrincipal;

    private Subject subject;
    private GSSContext context;

    CassandraGssAuthenticator(CassandraProxyServer.Config config) {
        this.host = config.targetHost;
        this.servicePrincipal = config.servicePrincipal;
        this.krb5ConfPath = config.krb5ConfPath;
        this.krb5CcName = config.krb5CcName;
        this.clientPrincipal = config.clientPrincipal;
    }

    synchronized byte[] initialToken() {
        ensureContext();
        return nextToken(new byte[0]);
    }

    synchronized byte[] challenge(byte[] input) {
        ensureContext();
        return nextToken(input);
    }

    private byte[] nextToken(byte[] input) {
        try {
            return Subject.doAs(subject, (java.security.PrivilegedExceptionAction<byte[]>) () -> {
                byte[] out = context.initSecContext(input, 0, input.length);
                return out == null ? new byte[0] : out;
            });
        } catch (java.security.PrivilegedActionException e) {
            Throwable cause = e.getException() == null ? e : e.getException();
            throw new IllegalStateException("GSS token generation failed", cause);
        } catch (Exception e) {
            throw new IllegalStateException("GSS token generation failed", e);
        }
    }

    private void ensureContext() {
        if (context != null) {
            return;
        }
        subject = login();
        context = createContext();
    }

    private Subject login() {
        try {
            if (krb5ConfPath != null && !krb5ConfPath.isBlank()) {
                System.setProperty("java.security.krb5.conf", krb5ConfPath);
            }
            Configuration jaas = new Configuration() {
                @Override
                public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                    Map<String, Object> opts = new HashMap<>();
                    opts.put("useTicketCache", "true");
                    opts.put("doNotPrompt", "true");
                    opts.put("refreshKrb5Config", "true");
                    opts.put("isInitiator", "true");
                    if (krb5CcName != null && !krb5CcName.isBlank()) {
                        opts.put("ticketCache", krb5CcName);
                    }
                    if (clientPrincipal != null && !clientPrincipal.isBlank()) {
                        opts.put("principal", clientPrincipal);
                    }
                    return new AppConfigurationEntry[] {
                        new AppConfigurationEntry(
                            KRB5_LOGIN,
                            AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                            opts)
                    };
                }
            };
            LoginContext lc = new LoginContext("", null, null, jaas);
            lc.login();
            return lc.getSubject();
        } catch (Exception e) {
            throw new IllegalStateException("Kerberos login failed", e);
        }
    }

    private GSSContext createContext() {
        try {
            GSSManager manager = GSSManager.getInstance();
            String service = servicePrincipal;
            if (service == null || service.isBlank()) {
                service = "cassandra/" + host;
            }
            GSSName server = manager.createName(service, GSSName.NT_HOSTBASED_SERVICE);
            GSSContext ctx = manager.createContext(server, KRB5_OID, null, GSSContext.DEFAULT_LIFETIME);
            ctx.requestMutualAuth(true);
            ctx.requestCredDeleg(false);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Kerberos context init failed", e);
        }
    }
}
