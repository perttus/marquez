/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */
package marquez.db.migrations;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * Dataset migration written in Java because SQLs used here will be reused in next migration when
 * processing content of `lineage_events` table to facets' tables.
 */
@Slf4j
public class V56_1__FacetViews implements JavaMigration {

  public static String getDatasetFacetsDefinitionSQL(Jdbi jdbi, String sourceTable) {
    return String.format(
        """
            WITH lineage_datasets AS (
                SELECT
                    jsonb_array_elements(coalesce(le.event -> 'inputs', '[]'::jsonb) || coalesce(le.event -> 'outputs', '[]'::jsonb)) AS dataset,
                    le.run_uuid,
                    le.event_time,
                    le.event_type,
                    le.created_at
                FROM %s le
            ),
            dataset_facets AS (
                SELECT
                    jsonb_object_keys as facet_name,
                    json_build_object(jsonb_object_keys, dataset -> 'facets' -> jsonb_object_keys)::jsonb as facet,
                    dataset ->> 'name' as dataset_name,
                    dataset ->> 'namespace' as dataset_namespace,
                    ld.*
                FROM lineage_datasets ld, jsonb_object_keys(coalesce(dataset -> 'facets', '{}'::jsonb))
            )
            SELECT
                %s AS uuid,
                COALESCE(df.created_at, df.event_time) AS created_at,
                dataset_symlinks.dataset_uuid AS dataset_uuid,
                df.run_uuid AS run_uuid,
                df.event_time AS lineage_event_time,
                df.event_type::VARCHAR(64) AS lineage_event_type,
                (
                    CASE
                    WHEN lower(facet_name) IN ('documentation', 'schema', 'datasource', 'description', 'lifecyclestatechange', 'version', 'columnlineage', 'ownership') then 'DATASET'
                    WHEN lower(facet_name) IN ('dataqualitymetrics', 'dataqualityassertions') then 'INPUT'
                    WHEN lower(facet_name) = 'outputstatistics' then 'OUTPUT'
                    ELSE 'UNKNOWN'
                    END
                )::VARCHAR(64) AS type,
                df.facet_name::VARCHAR(255) AS name,
                df.facet AS facet
            FROM dataset_facets df
            JOIN dataset_symlinks ON dataset_symlinks.name = dataset_name
            INNER JOIN namespaces ON dataset_symlinks.namespace_uuid = namespaces.uuid
            WHERE namespaces.name = dataset_namespace
            """,
        sourceTable, getGenerateUuidFunction(jdbi));
  }

  public static String getRunFacetsDefinitionSQL(Jdbi jdbi, String sourceTable) {
    return String.format(
        """
            SELECT
                %s AS uuid,
                COALESCE(le.created_at, le.event_time) AS created_at,
                le.run_uuid AS run_uuid,
                le.event_time AS lineage_event_time,
                le.event_type::VARCHAR(64) AS lineage_event_type,
                jsonb_object_keys::VARCHAR(255) as name,
                json_build_object(jsonb_object_keys, event -> 'run' -> 'facets' -> jsonb_object_keys)::jsonb as facet
            FROM %s le, jsonb_object_keys(coalesce(event -> 'run' -> 'facets', '{}'::jsonb))
            WHERE lower(jsonb_object_keys) != 'spark_unknown'
            """,
        getGenerateUuidFunction(jdbi), sourceTable);
  }

  public static String getJobFacetsDefinitionSQL(Jdbi jdbi, String sourceTable) {
    return String.format(
        """
          SELECT
              %s AS uuid,
              COALESCE(le.created_at, le.event_time) AS created_at,
              r.job_uuid AS job_uuid,
              le.run_uuid AS run_uuid,
              le.event_time AS lineage_event_time,
              le.event_type::VARCHAR(64) AS lineage_event_type,
              jsonb_object_keys::VARCHAR(255) as name,
              json_build_object(jsonb_object_keys, event -> 'job' -> 'facets' -> jsonb_object_keys)::jsonb as facet
          FROM %s le, runs r, jsonb_object_keys(coalesce(event -> 'job' -> 'facets', '{}'::jsonb))
          WHERE r.uuid = le.run_uuid
          """,
        getGenerateUuidFunction(jdbi), sourceTable);
  }

  @Override
  public MigrationVersion getVersion() {
    return MigrationVersion.fromVersion("56.1");
  }

  @Override
  public String getDescription() {
    return "CreateFacetViews";
  }

  @Override
  public Integer getChecksum() {
    return null;
  }

  @Override
  public boolean isUndo() {
    return false;
  }

  @Override
  public boolean isBaselineMigration() {
    return false;
  }

  @Override
  public boolean canExecuteInTransaction() {
    return false;
  }

  @Override
  public void migrate(Context context) {
    Jdbi jdbi = Jdbi.create(context.getConnection());

    final String datasetFacetQuery =
        "CREATE OR REPLACE VIEW dataset_facets_view AS "
            + getDatasetFacetsDefinitionSQL(jdbi, "lineage_events");

    final String runFacetQuery =
        "CREATE OR REPLACE VIEW run_facets_view AS "
            + getRunFacetsDefinitionSQL(jdbi, "lineage_events");

    final String jobFacetQuery =
        "CREATE OR REPLACE VIEW job_facets_view AS "
            + getJobFacetsDefinitionSQL(jdbi, "lineage_events");

    jdbi.inTransaction(handle -> handle.execute(datasetFacetQuery));
    jdbi.inTransaction(handle -> handle.execute(runFacetQuery));
    jdbi.inTransaction(handle -> handle.execute(jobFacetQuery));
  }

  /**
   * Since version 13 Postgresql contains built-in gen_random_uuid function. Up to version 12,
   * uuid-ossp extension needs to be registered. Creating extension requires superuser privileges.
   *
   * <p>In case of older version, this method registers extension and returns name of the older
   * function `uuid_generate_v4`. If function `gen_random_uuid` exists, its name is returned.
   *
   * @param jdbi
   * @return
   */
  private static String getGenerateUuidFunction(Jdbi jdbi) {
    try {
      jdbi.withHandle(h -> h.createCall("SELECT gen_random_uuid()").invoke());
      return "gen_random_uuid()";
    } catch (UnableToExecuteStatementException e1) {
      // need to try to install uuid-ossp
    }

    try {
      jdbi.withHandle(h -> h.createCall("SELECT uuid_generate_v4()").invoke());

      // uuid-ossp already installed
      return "uuid_generate_v4()";
    } catch (UnableToExecuteStatementException e1) {
      // need to try to install uuid-ossp
      log.info("creating uuid-ossp extension which requires superuser privileges");
      jdbi.withHandle(h -> h.createCall("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"").invoke());
      return "uuid_generate_v4()";
    }
  }
}