// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.catalog.local;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;

import org.apache.impala.analysis.ToSqlUtils;
import org.apache.impala.catalog.CatalogTest;
import org.apache.impala.catalog.ColumnStats;
import org.apache.impala.catalog.FeCatalogUtils;
import org.apache.impala.catalog.FeDb;
import org.apache.impala.catalog.FeFsPartition;
import org.apache.impala.catalog.FeFsTable;
import org.apache.impala.catalog.FeTable;
import org.apache.impala.catalog.FeView;
import org.apache.impala.catalog.HdfsPartition.FileDescriptor;
import org.apache.impala.catalog.Type;
import org.apache.impala.service.FeSupport;
import org.apache.impala.thrift.TCatalogObjectType;
import org.apache.impala.thrift.TResultSet;
import org.apache.impala.util.MetaStoreUtil;
import org.apache.impala.util.PatternMatcher;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

public class LocalCatalogTest {

  private LocalCatalog catalog_;

  @Before
  public void setupCatalog() {
    // FeSupport.loadLibrary(); BACKPORT NOTE: need 88fe1283f1499ad3ebc73a40189b2fe4b5e4a093
    catalog_ = LocalCatalog.create(/*defaultKuduMasterHosts=*/null);
  }

  @Test
  public void testDbs() throws Exception {
    FeDb functionalDb = catalog_.getDb("functional");
    assertNotNull(functionalDb);
    FeDb functionalSeqDb = catalog_.getDb("functional_seq");
    assertNotNull(functionalSeqDb);

    Set<FeDb> dbs = ImmutableSet.copyOf(
        catalog_.getDbs(PatternMatcher.MATCHER_MATCH_ALL));
    assertTrue(dbs.contains(functionalDb));
    assertTrue(dbs.contains(functionalSeqDb));

    dbs = ImmutableSet.copyOf(
        catalog_.getDbs(PatternMatcher.createHivePatternMatcher("*_seq")));
    assertFalse(dbs.contains(functionalDb));
    assertTrue(dbs.contains(functionalSeqDb));
  }

  @Test
  public void testListTables() throws Exception {
    Set<String> names = ImmutableSet.copyOf(catalog_.getTableNames(
        "functional", PatternMatcher.MATCHER_MATCH_ALL));
    assertTrue(names.contains("alltypes"));

    FeDb db = catalog_.getDb("functional");
    assertNotNull(db);
    FeTable t = catalog_.getTable("functional", "alltypes");
    assertNotNull(t);
    assertSame(t, db.getTable("alltypes"));
    assertSame(db, t.getDb());
    assertEquals("alltypes", t.getName());
    assertEquals("functional", t.getDb().getName());
    assertEquals("functional.alltypes", t.getFullName());
  }

  @Test
  public void testLoadTableBasics() throws Exception {
    FeDb functionalDb = catalog_.getDb("functional");
    CatalogTest.checkTableCols(functionalDb, "alltypes", 2,
        new String[]
          {"year", "month", "id", "bool_col", "tinyint_col", "smallint_col",
           "int_col", "bigint_col", "float_col", "double_col", "date_string_col",
           "string_col", "timestamp_col"},
        new Type[]
          {Type.INT, Type.INT, Type.INT,
           Type.BOOLEAN, Type.TINYINT, Type.SMALLINT,
           Type.INT, Type.BIGINT, Type.FLOAT,
           Type.DOUBLE, Type.STRING, Type.STRING,
           Type.TIMESTAMP});
    FeTable t = functionalDb.getTable("alltypes");
    assertEquals(7300, t.getNumRows());

    assertTrue(t instanceof LocalFsTable);
    FeFsTable fsTable = (FeFsTable) t;
    assertEquals(MetaStoreUtil.DEFAULT_NULL_PARTITION_KEY_VALUE,
        fsTable.getNullPartitionKeyValue());

    // Stats should have one row per partition, plus a "total" row.
    TResultSet stats = fsTable.getTableStats();
    assertEquals(25, stats.getRowsSize());
  }

  @Test
  public void testPartitioning() throws Exception {
    FeFsTable t = (FeFsTable) catalog_.getTable("functional",  "alltypes");
    // TODO(todd): once we support file descriptors in LocalCatalog,
    // run the full test.
    CatalogTest.checkAllTypesPartitioning(t, /*checkFileDescriptors=*/false);
  }

  /**
   * Test that partitions with a NULL value can be properly loaded.
   */
  @Test
  public void testEmptyPartitionValue() throws Exception {
    FeFsTable t = (FeFsTable) catalog_.getTable("functional",  "alltypesagg");
    // This table has one partition with a NULL value for the 'day'
    // clustering column.
    int dayCol = t.getColumn("day").getPosition();
    Set<Long> ids = t.getNullPartitionIds(dayCol);
    assertEquals(1,  ids.size());
    FeFsPartition partition = FeCatalogUtils.loadPartition(
        t, Iterables.getOnlyElement(ids));
    assertTrue(partition.getPartitionValue(dayCol).isNullLiteral());
  }

  @Test
  public void testLoadFileDescriptors() throws Exception {
    FeFsTable t = (FeFsTable) catalog_.getTable("functional",  "alltypes");
    int totalFds = 0;
    for (FeFsPartition p: FeCatalogUtils.loadAllPartitions(t)) {
      List<FileDescriptor> fds = p.getFileDescriptors();
      totalFds += fds.size();
      for (FileDescriptor fd : fds) {
        assertTrue(fd.getFileLength() > 0);
        assertEquals(fd.getNumFileBlocks(), 1);
        assertEquals(3, fd.getFbFileBlock(0).diskIdsLength());
      }
    }
    assertEquals(24, totalFds);
  }

  @Test
  public void testColumnStats() throws Exception {
    FeFsTable t = (FeFsTable) catalog_.getTable("functional",  "alltypesagg");
    // Verify expected stats for a partitioning column.
    // 'days' has 10 non-NULL plus one NULL partition
    ColumnStats stats = t.getColumn("day").getStats();
    assertEquals(11, stats.getNumDistinctValues());
    assertEquals(1, stats.getNumNulls());

    // Verify expected stats for timestamp.
    stats = t.getColumn("timestamp_col").getStats();
    assertEquals(10210, stats.getNumDistinctValues());
    assertEquals(-1, stats.getNumNulls());
  }

  @Test
  public void testView() throws Exception {
    FeView v = (FeView) catalog_.getTable("functional",  "alltypes_view");
    assertEquals(TCatalogObjectType.VIEW, v.getCatalogObjectType());
    assertEquals("SELECT * FROM functional.alltypes", v.getQueryStmt().toSql());
  }

  @Test
  public void testKuduTable() throws Exception {
    LocalKuduTable t = (LocalKuduTable) catalog_.getTable("functional_kudu",  "alltypes");
    assertEquals("id,bool_col,tinyint_col,smallint_col,int_col," +
        "bigint_col,float_col,double_col,date_string_col,string_col," +
        "timestamp_col,year,month", Joiner.on(",").join(t.getColumnNames()));
    // Assert on the generated SQL for the table, but not the table properties, since
    // those might change based on whether this test runs before or after other
    // tests which compute stats, etc.
    Assert.assertTrue(ToSqlUtils.getCreateTableSql(t).startsWith(
        "CREATE TABLE functional_kudu.alltypes (\n" +
        "  id INT NOT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  bool_col BOOLEAN NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  tinyint_col TINYINT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  smallint_col SMALLINT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  int_col INT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  bigint_col BIGINT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  float_col FLOAT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  double_col DOUBLE NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  date_string_col STRING NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  string_col STRING NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  timestamp_col TIMESTAMP NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  year INT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  month INT NULL ENCODING AUTO_ENCODING COMPRESSION DEFAULT_COMPRESSION,\n" +
        "  PRIMARY KEY (id)\n" +
        ")\n" +
        "PARTITION BY HASH (id) PARTITIONS 3\n" +
        "STORED AS KUDU\n" +
        "TBLPROPERTIES"));
  }

  @Test
  public void testHbaseTable() throws Exception {
    LocalHbaseTable t = (LocalHbaseTable) catalog_.getTable("functional_hbase",
        "alltypes");
    Assert.assertTrue(ToSqlUtils.getCreateTableSql(t).startsWith(
        "CREATE EXTERNAL TABLE functional_hbase.alltypes (\n" +
        "  id INT COMMENT 'Add a comment',\n" +
        "  bigint_col BIGINT,\n" +
        "  bool_col BOOLEAN,\n" +
        "  date_string_col STRING,\n" +
        "  double_col DOUBLE,\n" +
        "  float_col FLOAT,\n" +
        "  int_col INT,\n" +
        "  month INT,\n" +
        "  smallint_col SMALLINT,\n" +
        "  string_col STRING,\n" +
        "  timestamp_col TIMESTAMP,\n" +
        "  tinyint_col TINYINT,\n" +
        "  year INT\n" +
        ")\n" +
        "STORED BY 'org.apache.hadoop.hive.hbase.HBaseStorageHandler'\n" +
        "WITH SERDEPROPERTIES ('hbase.columns.mapping'=':key,d:bool_col,d:tinyint_col," +
        "d:smallint_col,d:int_col,d:bigint_col,d:float_col,d:double_col," +
        "d:date_string_col,d:string_col,d:timestamp_col,d:year,d:month', " +
        "'serialization.format'='1')"
    ));
  }

  /**
   * Test loading an Avro table which has an explicit avro schema. The schema
   * should override the columns from the HMS.
   */
  @Test
  public void testAvroExplicitSchema() throws Exception {
    FeFsTable t = (FeFsTable)catalog_.getTable("functional_avro", "zipcode_incomes");
    assertNotNull(t.toThriftDescriptor(0, null).hdfsTable.avroSchema);
    assertTrue(t.usesAvroSchemaOverride());
  }

  /**
   * Test loading a table which does not have an explicit avro schema property.
   * In this case we create an avro schema on demand from the table schema.
   */
  @Test
  public void testAvroImplicitSchema() throws Exception {
    FeFsTable t = (FeFsTable)catalog_.getTable("functional_avro_snap", "no_avro_schema");
    assertNotNull(t.toThriftDescriptor(0, null).hdfsTable.avroSchema);
    // The tinyint column should get promoted to INT to be Avro-compatible.
    assertEquals(t.getColumn("tinyint_col").getType(), Type.INT);
    assertTrue(t.usesAvroSchemaOverride());
  }
}
