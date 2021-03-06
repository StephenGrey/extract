package org.icij.spewer.http;

import org.apache.commons.io.FilenameUtils;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.client.CredentialsProvider; //NEW
import org.apache.http.auth.UsernamePasswordCredentials; //NEW 
import org.apache.http.impl.client.BasicCredentialsProvider; //NEW
import org.apache.http.auth.AuthScope; //NEW

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.auth.AuthState;
import org.apache.http.auth.Credentials;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;


/**
 * Extends {@link HttpClientBuilder} with the ability to pin a certificate and a hostname.
 */
 
 
public class PinnedHttpClientBuilder extends HttpClientBuilder {

	private HostnameVerifier hostnameVerifier = null;
	private SSLContext sslContext = null;
        private String indexPassword = null;
        private String indexUsername = null;
        private Logger logger = LoggerFactory.getLogger(getClass());

	public static PinnedHttpClientBuilder createWithDefaults() {
                final PinnedHttpClientBuilder builder = new PinnedHttpClientBuilder();
//		final CredentialsProvider provider = new BasicCredentialsProvider(); //NEW
//		final UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(
//            "solr","SolrRocks"); //NEW
//		provider.setCredentials(AuthScope.ANY, credentials);                //NEW

		builder
			.setMaxConnPerRoute(32)
			.setMaxConnTotal(128)
			.disableRedirectHandling()
			.setRetryHandler(new CountdownHttpRequestRetryHandler());

		return builder;
	}

	public PinnedHttpClientBuilder() {
		super();
	}
	
        public PinnedHttpClientBuilder setUserPassword(final String username, final String password) {
            if (null == password) {
			indexPassword = null;
			return this;
                } else {
                        indexPassword= password;
            }
            if (null == username) {
			indexUsername = null;
			return this;
                } else {
                        indexUsername= username;
            }
            
            return this;
        }
        public PinnedHttpClientBuilder setCredentials(){
            if (null != indexUsername && null != indexPassword ){
            logger.info("Setting username and password");
//            final PinnedHttpClientBuilder builder = new PinnedHttpClientBuilder();
            final CredentialsProvider provider = new BasicCredentialsProvider(); 
            final UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(
            indexUsername,indexPassword); //
	    provider.setCredentials(AuthScope.ANY, credentials);
            this.addInterceptorFirst(new PreemptiveAuthInterceptor());
            this.setDefaultCredentialsProvider(provider);
            }
            else {
            logger.info("Username and password not set. No authentication set");
            }
            return this;//NEW
        }
        
   
        public PinnedHttpClientBuilder setVerifyHostname(final String verifyHostname) {
		if (null == verifyHostname) {
			hostnameVerifier = null;
			return this;
		} else if (verifyHostname.equals("*")) {
			hostnameVerifier = NoopHostnameVerifier.INSTANCE;
		} else {
			hostnameVerifier = new BodgeHostnameVerifier(verifyHostname);
		}

		return this;
	}

	public PinnedHttpClientBuilder pinCertificate(final String trustStorePath) throws RuntimeException {
		return pinCertificate(trustStorePath, "");
	}

	public PinnedHttpClientBuilder pinCertificate(final String trustStorePath, final String trustStorePassword)
		throws RuntimeException {
		if (null != trustStorePath) {
			try {
				final TrustManagerFactory trustManager = TrustManagerFactory.getInstance("X509");

				trustManager.init(createTrustStore(trustStorePath, trustStorePassword));

				sslContext = SSLContext.getInstance("TLS");
				sslContext.init(null, trustManager.getTrustManagers(), null);
			} catch (IOException | NoSuchAlgorithmException | CertificateException | KeyStoreException
				| KeyManagementException e) {
				throw new RuntimeException("Unable to pin certificate: " + trustStorePath + ".", e);
			}
		} else {
			sslContext = null;
		}

		return this;
	}

	public CloseableHttpClient build() {
		if (null != hostnameVerifier) {
			super.setSSLHostnameVerifier(hostnameVerifier);
		}

		if (null != sslContext) {
			super.setSSLSocketFactory(new SSLConnectionSocketFactory(sslContext, hostnameVerifier));
		}

		return super.build();
	}

	public static KeyStore createTrustStore(final String trustStorePath, final String trustStorePassword)
		throws IOException, NoSuchAlgorithmException, CertificateException, KeyStoreException {

		final String trustStoreExtension = FilenameUtils.getExtension(trustStorePath).toUpperCase(Locale.ROOT);
		final String trustStoreType;

		// Key store types are defined in Oracle's Cryptography Standard Algorithm Name Documentation:
		// http://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#KeyStore
		if (trustStoreExtension.equals("P12")) {
			trustStoreType = "PKCS12";
		} else {
			trustStoreType = KeyStore.getDefaultType();
		}

		final KeyStore trustStore = KeyStore.getInstance(trustStoreType);

		try (
			final InputStream input = new BufferedInputStream(new FileInputStream(trustStorePath))
		) {
			if (trustStoreExtension.equals("PEM") || trustStoreExtension.equals("DER")) {
				final X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
					.generateCertificate(input);

				// Create an empty key store.
				// This operation should never throw an exception.
				trustStore.load(null, null);
				trustStore.setCertificateEntry(Integer.toString(1), certificate);
			} else {
				trustStore.load(input, trustStorePassword.toCharArray());
			}
		}

		return trustStore;
	}

	public static class BodgeHostnameVerifier implements HostnameVerifier {

		private static final HostnameVerifier defaultVerifier = new DefaultHostnameVerifier();
		private final String verifyHostname;

		public BodgeHostnameVerifier(final String verifyHostname) {
			super();
			this.verifyHostname = verifyHostname;
		}

		@Override
		public final boolean verify(final String host, final SSLSession session) {
			return defaultVerifier.verify(verifyHostname, session);
		}
        }
        static class PreemptiveAuthInterceptor implements HttpRequestInterceptor {

            public void process(final HttpRequest request, final HttpContext context) throws HttpException, IOException {
            AuthState authState = (AuthState) context.getAttribute(HttpClientContext.TARGET_AUTH_STATE);
            // If no auth scheme available yet, try to initialize it
            // preemptively
            if (authState.getAuthScheme() == null) {
                CredentialsProvider credsProvider = (CredentialsProvider) 
                        context.getAttribute(HttpClientContext.CREDS_PROVIDER);
                HttpHost targetHost = (HttpHost) context.getAttribute(HttpCoreContext.HTTP_TARGET_HOST);
                AuthScope authScope = new AuthScope(targetHost.getHostName(), targetHost.getPort());
                Credentials creds = credsProvider.getCredentials(authScope);
                if(creds == null){
               }
                authState.update(new BasicScheme(), creds);
            }

            }
        }
        
            
}
