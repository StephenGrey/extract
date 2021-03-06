package org.icij.extract.tasks;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.icij.extract.IndexType;
import org.icij.spewer.http.PinnedHttpClientBuilder;
import org.icij.task.DefaultTask;
import org.icij.task.annotation.Option;
import org.icij.task.annotation.Task;

import java.io.IOException;

/**
 * A task that sends a commit message to an indexer API endpoint.
 *
 *
 */
@Task("Send a hard or soft commit message to the index.")
@Option(name = "indexType", description = "Specify the index type. For now, the only valid value is " +
		"\"solr\" (the default).", parameter = "type")
@Option(name = "softCommit", description = "Performs a soft commit. Makes index changes visible while " +
		"neither fsync-ing index files nor writing a new index descriptor. This could lead to data loss if Solr is " +
		"terminated unexpectedly.")
@Option(name = "address", description = "Index core API endpoint address.", code = "s", parameter = "url")
@Option(name = "indexUsername", description = "The index server's username.", code = "U", parameter = "username")
@Option(name = "indexPassword", description = "The index server's password.", code= "P", parameter = "password")
@Option(name = "serverCertificate", description = "The index server's public certificate, used for " +
		"certificate pinning. Supported formats are PEM, DER, PKCS #12 and JKS.", parameter = "path")
@Option(name = "verifyHost", description = "Verify the index server's public certificate against the " +
		"specified host. Use the wildcard \"*\" to disable verification.", parameter = "hostname")
public class CommitTask extends DefaultTask<Integer> {

	@Override
	public Integer call() throws Exception {
		final IndexType indexType = options.get("indexType").value(IndexType::parse).orElse(IndexType.SOLR);
		final boolean softCommit = options.get("softCommit").parse().asBoolean().orElse(false);

		if (IndexType.SOLR == indexType) {
			return commitSolr(softCommit).getQTime();
		} else {
			throw new IllegalStateException("Not implemented.");
		}
	}

	/**
	 * Send a commit message to a Solr API endpoint.
	 *
	 * @param softCommit {@literal true} to commit without flushing changes to disk
	 * @return The response details from Solr.
	 */
	private UpdateResponse commitSolr(final boolean softCommit) {
		try (final CloseableHttpClient httpClient = PinnedHttpClientBuilder.createWithDefaults()
				.setVerifyHostname(options.get("verifyHost").value().orElse(null))
                        	.setUserPassword(options.get("indexUsername").value().orElse(null),options.get("indexPassword").value().orElse(null))
                                .setCredentials()
				.pinCertificate(options.get("serverCertificate").value().orElse(null))
				.build();
		     final SolrClient client = new HttpSolrClient.Builder(options.get("address").value().orElse
				     ("http://127.0.0.1:8983/solr/"))
				     .withHttpClient(httpClient)
				     .build()) {
			return client.commit(true, true, softCommit);
		} catch (SolrServerException e) {
			throw new RuntimeException("Unable to commit to Solr.", e);
		} catch (IOException e) {
			throw new RuntimeException("There was an error while communicating with Solr.", e);
		}
	}
}
