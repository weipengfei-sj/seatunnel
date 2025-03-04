/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.connectors.seatunnel.paimon.catalog;

import org.apache.seatunnel.api.configuration.ReadonlyConfig;
import org.apache.seatunnel.api.table.catalog.Catalog;
import org.apache.seatunnel.api.table.catalog.CatalogTable;
import org.apache.seatunnel.api.table.catalog.Column;
import org.apache.seatunnel.api.table.catalog.TablePath;
import org.apache.seatunnel.api.table.catalog.TableSchema;
import org.apache.seatunnel.api.table.catalog.exception.CatalogException;
import org.apache.seatunnel.api.table.catalog.exception.DatabaseAlreadyExistException;
import org.apache.seatunnel.api.table.catalog.exception.DatabaseNotExistException;
import org.apache.seatunnel.api.table.catalog.exception.TableAlreadyExistException;
import org.apache.seatunnel.api.table.catalog.exception.TableNotExistException;
import org.apache.seatunnel.api.table.converter.BasicTypeDefine;
import org.apache.seatunnel.connectors.seatunnel.paimon.config.PaimonConfig;
import org.apache.seatunnel.connectors.seatunnel.paimon.config.PaimonSinkConfig;
import org.apache.seatunnel.connectors.seatunnel.paimon.utils.SchemaUtil;

import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.Table;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;

import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

@Slf4j
public class PaimonCatalog implements Catalog, PaimonTable {
    private static final String DEFAULT_DATABASE = "default";

    private String catalogName;
    private ReadonlyConfig readonlyConfig;
    private PaimonCatalogLoader paimonCatalogLoader;
    private org.apache.paimon.catalog.Catalog catalog;

    public PaimonCatalog(String catalogName, ReadonlyConfig readonlyConfig) {
        this.readonlyConfig = readonlyConfig;
        this.catalogName = catalogName;
        this.paimonCatalogLoader = new PaimonCatalogLoader(new PaimonConfig(readonlyConfig));
    }

    @Override
    public void open() throws CatalogException {
        this.catalog = paimonCatalogLoader.loadCatalog();
    }

    @Override
    public void close() throws CatalogException {
        if (catalog != null && catalog instanceof Closeable) {
            try {
                ((Closeable) catalog).close();
            } catch (IOException e) {
                log.error("Error while closing IcebergCatalog.", e);
                throw new CatalogException(e);
            }
        }
    }

    @Override
    public String name() {
        return this.catalogName;
    }

    @Override
    public String getDefaultDatabase() throws CatalogException {
        return DEFAULT_DATABASE;
    }

    @Override
    public boolean databaseExists(String databaseName) throws CatalogException {
        return catalog.databaseExists(databaseName);
    }

    @Override
    public List<String> listDatabases() throws CatalogException {
        return catalog.listDatabases();
    }

    @Override
    public List<String> listTables(String databaseName)
            throws CatalogException, DatabaseNotExistException {
        try {
            return catalog.listTables(databaseName);
        } catch (org.apache.paimon.catalog.Catalog.DatabaseNotExistException e) {
            throw new DatabaseNotExistException(this.catalogName, databaseName);
        }
    }

    @Override
    public boolean tableExists(TablePath tablePath) throws CatalogException {
        return catalog.tableExists(toIdentifier(tablePath));
    }

    @Override
    public CatalogTable getTable(TablePath tablePath)
            throws CatalogException, TableNotExistException {
        try {
            FileStoreTable paimonFileStoreTableTable = (FileStoreTable) getPaimonTable(tablePath);
            return toCatalogTable(paimonFileStoreTableTable, tablePath);
        } catch (Exception e) {
            throw new TableNotExistException(this.catalogName, tablePath);
        }
    }

    @Override
    public Table getPaimonTable(TablePath tablePath)
            throws CatalogException, TableNotExistException {
        try {
            return catalog.getTable(toIdentifier(tablePath));
        } catch (org.apache.paimon.catalog.Catalog.TableNotExistException e) {
            throw new TableNotExistException(this.catalogName, tablePath);
        }
    }

    @Override
    public void createTable(TablePath tablePath, CatalogTable table, boolean ignoreIfExists)
            throws TableAlreadyExistException, DatabaseNotExistException, CatalogException {
        try {
            Schema paimonSchema =
                    SchemaUtil.toPaimonSchema(
                            table.getTableSchema(), new PaimonSinkConfig(readonlyConfig));
            catalog.createTable(toIdentifier(tablePath), paimonSchema, ignoreIfExists);
        } catch (org.apache.paimon.catalog.Catalog.TableAlreadyExistException e) {
            throw new TableAlreadyExistException(this.catalogName, tablePath);
        } catch (org.apache.paimon.catalog.Catalog.DatabaseNotExistException e) {
            throw new DatabaseNotExistException(this.catalogName, tablePath.getDatabaseName());
        }
    }

    @Override
    public void dropTable(TablePath tablePath, boolean ignoreIfNotExists)
            throws TableNotExistException, CatalogException {
        try {
            catalog.dropTable(toIdentifier(tablePath), ignoreIfNotExists);
        } catch (org.apache.paimon.catalog.Catalog.TableNotExistException e) {
            throw new TableNotExistException(this.catalogName, tablePath);
        }
    }

    @Override
    public void createDatabase(TablePath tablePath, boolean ignoreIfExists)
            throws DatabaseAlreadyExistException, CatalogException {
        try {
            catalog.createDatabase(tablePath.getDatabaseName(), ignoreIfExists);
        } catch (org.apache.paimon.catalog.Catalog.DatabaseAlreadyExistException e) {
            throw new DatabaseAlreadyExistException(this.catalogName, tablePath.getDatabaseName());
        }
    }

    @Override
    public void dropDatabase(TablePath tablePath, boolean ignoreIfNotExists)
            throws DatabaseNotExistException, CatalogException {
        try {
            catalog.dropDatabase(tablePath.getDatabaseName(), ignoreIfNotExists, true);
        } catch (Exception e) {
            throw new DatabaseNotExistException(this.catalogName, tablePath.getDatabaseName());
        }
    }

    private CatalogTable toCatalogTable(
            FileStoreTable paimonFileStoreTableTable, TablePath tablePath) {
        org.apache.paimon.schema.TableSchema schema = paimonFileStoreTableTable.schema();
        List<DataField> dataFields = schema.fields();
        TableSchema.Builder builder = TableSchema.builder();
        dataFields.forEach(
                dataField -> {
                    BasicTypeDefine.BasicTypeDefineBuilder<DataType> typeDefineBuilder =
                            BasicTypeDefine.<DataType>builder()
                                    .name(dataField.name())
                                    .comment(dataField.description())
                                    .nativeType(dataField.type());
                    Column column = SchemaUtil.toSeaTunnelType(typeDefineBuilder.build());
                    builder.column(column);
                });

        List<String> partitionKeys = schema.partitionKeys();

        return CatalogTable.of(
                org.apache.seatunnel.api.table.catalog.TableIdentifier.of(
                        catalogName, tablePath.getDatabaseName(), tablePath.getTableName()),
                builder.build(),
                paimonFileStoreTableTable.options(),
                partitionKeys,
                null,
                catalogName);
    }

    private Identifier toIdentifier(TablePath tablePath) {
        return Identifier.create(tablePath.getDatabaseName(), tablePath.getTableName());
    }
}
