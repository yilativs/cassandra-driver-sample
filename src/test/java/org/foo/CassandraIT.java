package org.foo;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import javax.naming.ConfigurationException;

import org.apache.thrift.transport.TTransportException;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.HostDistance;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ProtocolOptions;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SocketOptions;
import com.datastax.driver.core.policies.RoundRobinPolicy;
import com.datastax.driver.extras.codecs.jdk8.InstantCodec;

public class CassandraIT {

	private static Cluster cluster;
	private static Session session;

	@BeforeClass
	public static void beforeClassCreateClusterConnectionAndTable() throws IOException, InterruptedException, ConfigurationException, TTransportException {
		startEmbeddedCassandraUnit();
		setupCluster();
		setupKeyspace();
	}

	private static void startEmbeddedCassandraUnit() throws ConfigurationException, TTransportException, IOException {
		EmbeddedCassandraServerHelper.startEmbeddedCassandra("cassandra.yaml"); //does nothing if already started
	}

	private static void setupCluster() throws InterruptedException {
		PoolingOptions poolingOptions = new PoolingOptions();
		poolingOptions
				.setCoreConnectionsPerHost(HostDistance.LOCAL, 4)
				.setMaxConnectionsPerHost(HostDistance.LOCAL, 10)
				.setCoreConnectionsPerHost(HostDistance.REMOTE, 2)
				.setMaxConnectionsPerHost(HostDistance.REMOTE, 4);

		Thread.sleep(15_000);//FIXME find out how to make driver wait till cassandra server starts via setConnectTimeoutMillis
		SocketOptions socketOptions = new SocketOptions();
		//		socketOptions.setConnectTimeoutMillis(60_000);
		cluster = Cluster.builder()
				.withClusterName("Test Cluster")//default cluster
				.withCredentials("cassandra", "cassandra")//default credentials
				.withCompression(ProtocolOptions.Compression.SNAPPY)//this is protocol compression, not storage compression, hence it's ok to use fast less memory consuming compression
				.withPoolingOptions(poolingOptions)
				.withLoadBalancingPolicy(new RoundRobinPolicy())//https://docs.datastax.com/en/developer/java-driver/3.1/manual/load_balancing/\
				.withoutJMXReporting()
				.withoutMetrics()
				.withSocketOptions(socketOptions)
				.addContactPoint("127.0.0.1").build();

		cluster.getConfiguration().getCodecRegistry().register(InstantCodec.instance);//https://docs.datastax.com/en/developer/java-driver/3.5/manual/custom_codecs/extras/
	}

	private static void setupKeyspace() {
		session = cluster.connect();
		//TODO https://docs.datastax.com/en/cql/3.1/cql/cql_reference/create_keyspace_r.html
		session.execute("CREATE KEYSPACE IF NOT EXISTS fooKeySpace WITH replication = {'class':'SimpleStrategy', 'replication_factor':1}");
		//can be NetworkTopologyStrategy instead of SimpleStrategy
		//		session.execute("CREATE KEYSPACE IF NOT EXISTS fooKeySpace WITH replication = {'class' : 'NetworkTopologyStrategy', 'dc1' : 3, 'dc2' : 2}");

	}

	@Before
	public void beforeTest() {
		createTable();
	}

	private void createTable() {
		session.execute("CREATE TABLE IF NOT EXISTS fooKeySpace.fooTable (" +
				"id UUID, " +
				"dateTime timestamp, " +
				"description text, " +
				"PRIMARY KEY(id)" +
				") " +
				"WITH default_time_to_live=3 and " + // ttl=3 seconds
				"compression = { 'sstable_compression' : 'LZ4Compressor', 'chunk_length_kb' : 128 }");//options  are DeflateCompressor,SnappyCompressor,LZ4Compressor  , see https://docs.datastax.com/en/cassandra/2.1/cassandra/operations/ops_config_compress_t.html
	}

	@After
	public void afterTest() {
		dropTable();
	}

	private void dropTable() {
		session.execute("DROP TABLE fooKeySpace.fooTable");//options  are DeflateCompressor,SnappyCompressor,LZ4Compressor  , see https://docs.datastax.com/en/cassandra/2.1/cassandra/operations/ops_config_compress_t.html
	}

	@AfterClass
	public static void afterClass() {
		if (session != null && !session.isClosed()) {
			session.close();
		}
		if (cluster != null && !cluster.isClosed()) {
			cluster.close();
		}
	}

	@Test
	public void validatesExpectedVersion() {
		ResultSet resultSet = session.execute("select release_version from system.local");
		Row row = resultSet.one();
		Assert.assertEquals("3.11.2", row.getString("release_version"));
	}

	@Test
	public void intertsRecordUsingBindingByIndex() {
		UUID uuid = UUID.randomUUID();
		Date timesamp = new Date();
		String description = "foo text";

		//see https://docs.datastax.com/en/developer/java-driver/3.5/manual/statements/prepared/
		PreparedStatement preparedStatement = session.prepare("insert into fooKeySpace.fooTable  (id, dateTime, description) values (?, ?, ?)");
		session.execute(preparedStatement.bind(uuid, timesamp, description));

		Map<String, Object> parameterToValue = Collections.singletonMap("id", uuid);
		ResultSet resultSet = session.execute("select id, dateTime, description from  fooKeySpace.fooTable where id = :id", parameterToValue);
		Row row = resultSet.one();
		Assert.assertEquals(uuid, row.get(0, UUID.class));
		Assert.assertEquals(timesamp, row.get(1, Date.class));
		Assert.assertEquals(description, row.get(2, String.class));
	}

	@Test
	public void intertsRecordUsingBindingByName() {
		UUID uuid = UUID.randomUUID();
		Date timesamp = new Date();
		String description = "foo text";

		//see https://docs.datastax.com/en/developer/java-driver/3.5/manual/statements/prepared/

		PreparedStatement preparedStatement = session.prepare("insert into fooKeySpace.fooTable  (id, dateTime, description) values (:i, :t, :d)");
		BoundStatement bindStatement = preparedStatement.bind(uuid, timesamp, description);
		bindStatement.set("i", uuid, UUID.class);
		bindStatement.set("t", timesamp, Date.class);
		bindStatement.set("d", description, String.class);
		session.execute(bindStatement);

		Map<String, Object> parameterToValue = Collections.singletonMap("id", uuid);
		ResultSet resultSet = session.execute("select id, dateTime, description from  fooKeySpace.fooTable where id = :id", parameterToValue);
		Row row = resultSet.one();
		Assert.assertEquals(uuid, row.get(0, UUID.class));
		Assert.assertEquals(timesamp, row.get(1, Date.class));
		Assert.assertEquals(description, row.get(2, String.class));
	}

	@Test
	public void intertsRecordUsingBindingByNameWithInstantForTimestamp() {
		UUID uuid = UUID.randomUUID();
		Instant timesamp = Instant.now();
		String description = "foo text";

		//see https://docs.datastax.com/en/developer/java-driver/3.5/manual/statements/prepared/

		PreparedStatement preparedStatement = session.prepare("insert into fooKeySpace.fooTable  (id, dateTime, description) values (:i, :t, :d)");
		BoundStatement bindStatement = preparedStatement.bind(uuid, timesamp, description);
		bindStatement.set("i", uuid, UUID.class);
		bindStatement.set("t", timesamp, Instant.class);
		bindStatement.set("d", description, String.class);
		session.execute(bindStatement);

		Map<String, Object> parameterToValue = Collections.singletonMap("id", uuid);
		ResultSet resultSet = session.execute("select id, dateTime, description from  fooKeySpace.fooTable where id = :id", parameterToValue);
		Row row = resultSet.one();
		Assert.assertEquals(uuid, row.get(0, UUID.class));
		Assert.assertEquals(timesamp, row.get(1, Instant.class));
		Assert.assertEquals(description, row.get(2, String.class));
	}

	@Test
	public void updatesInsertedRecord() {
		UUID uuid = UUID.randomUUID();
		Instant timesamp = Instant.now();
		String description = "foo text";
		session.execute("insert into fooKeySpace.fooTable  (id, dateTime, description) values (?, ?, ?)", uuid, timesamp, description);
		ResultSet resultSetAfterInsertion = session.execute("select id, dateTime, description from  fooKeySpace.fooTable where id = ?", uuid);
		Row row = resultSetAfterInsertion.one();
		Assert.assertEquals(uuid, row.get(0, UUID.class));
		Assert.assertEquals(timesamp, row.get(1, Instant.class));
		Assert.assertEquals(description, row.get(2, String.class));

		String newDescription = "new description";
		session.execute("update fooKeySpace.fooTable set description = ? where id = ?", newDescription, uuid);
		ResultSet resultSetAfterUpdate = session.execute("select id, dateTime, description from  fooKeySpace.fooTable where id = ?", uuid);
		Row updatedRow = resultSetAfterUpdate.one();
		Assert.assertEquals(uuid, updatedRow.get(0, UUID.class));
		Assert.assertEquals(timesamp, updatedRow.get(1, Instant.class));
		Assert.assertEquals(newDescription, updatedRow.get(2, String.class));
	}

	@Test
	public void delitsInsertedRecord() {
		UUID uuid = UUID.randomUUID();
		Instant timesamp = Instant.now();
		String description = "foo text";
		session.execute("insert into fooKeySpace.fooTable  (id, dateTime, description) values (?, ?, ?)", uuid, timesamp, description);
		ResultSet resultSetAfterInsertion = session.execute("select id, dateTime, description from  fooKeySpace.fooTable where id = ?", uuid);
		Row row = resultSetAfterInsertion.one();
		Assert.assertEquals(uuid, row.get(0, UUID.class));
		Assert.assertEquals(timesamp, row.get(1, Instant.class));
		Assert.assertEquals(description, row.get(2, String.class));
		session.execute("delete from fooKeySpace.fooTable where id = ?", uuid);
		ResultSet resultSetAfterDeletion = session.execute("select id, dateTime, description from  fooKeySpace.fooTable where id = ?", uuid);
		Assert.assertTrue(resultSetAfterDeletion.isExhausted());
		//same as above, though less efficient
		Assert.assertNull(resultSetAfterDeletion.one());
	}

	@Test
	public void deletesByIndividualTTL() throws InterruptedException {
		//see also https://docs.datastax.com/en/cql/3.3/cql/cql_using/useExpireExample.html
		UUID uuid = UUID.randomUUID();
		Instant timesamp = Instant.now();
		String description = "foo text";
		session.execute("insert into fooKeySpace.fooTable  (id, dateTime, description) values (?, ?, ?) using ttl 1", uuid, timesamp, description);
		ResultSet resultSetAfterInsertion = session.execute("select id, dateTime, description from  fooKeySpace.fooTable where id = ?", uuid);
		Row row = resultSetAfterInsertion.one();
		Assert.assertEquals(uuid, row.get(0, UUID.class));
		Assert.assertEquals(timesamp, row.get(1, Instant.class));
		Assert.assertEquals(description, row.get(2, String.class));

		Thread.sleep(1 * 1000);//we have set TTL to 1 seconds so that should be enough
		ResultSet resultSetAfterDeletion = session.execute("select id, dateTime, description from  fooKeySpace.fooTable where id = ?", uuid);
		Assert.assertTrue(resultSetAfterDeletion.isExhausted());
		//same as above, though less efficient
		Assert.assertNull(resultSetAfterDeletion.one());
	}

	@Test
	public void deletesByDefaultTTL() throws InterruptedException {
		//see also https://docs.datastax.com/en/cql/3.3/cql/cql_using/useExpireExample.html
		UUID uuid = UUID.randomUUID();
		Instant timesamp = Instant.now();
		String description = "foo text";
		session.execute("insert into fooKeySpace.fooTable  (id, dateTime, description) values (?, ?, ?)", uuid, timesamp, description);
		ResultSet resultSetAfterInsertion = session.execute("select id, dateTime, description from  fooKeySpace.fooTable where id = ?", uuid);
		Row row = resultSetAfterInsertion.one();
		Assert.assertEquals(uuid, row.get(0, UUID.class));
		Assert.assertEquals(timesamp, row.get(1, Instant.class));
		Assert.assertEquals(description, row.get(2, String.class));

		Thread.sleep(3 * 1000);//we have set TTL to 3 seconds so that should be enough
		ResultSet resultSetAfterDeletion = session.execute("select id, dateTime, description from  fooKeySpace.fooTable where id = ?", uuid);
		Assert.assertTrue(resultSetAfterDeletion.isExhausted());
		//same as above, though less efficient
		Assert.assertNull(resultSetAfterDeletion.one());
	}

}
