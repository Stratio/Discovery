(ns metabase.driver.generic-sql
  "Shared code for drivers for SQL databases using their respective JDBC drivers under the hood."
  (:require [clojure
             [set :as set]
             [string :as str]]
            [clojure.java.jdbc :as jdbc]
            [clojure.math.numeric-tower :as math]
            [clojure.tools.logging :as log]
            [honeysql
             [core :as hsql]
             [format :as hformat]]
            [metabase
             [db :as db]
             [driver :as driver]
             [util :as u]]
            [metabase.models
             [field :as field]
             [table :as table]]
            metabase.query-processor.interface
            [metabase.util
             [honeysql-extensions :as hx]
             [ssh :as ssh]]
            [schema.core :as s])
  (:import [clojure.lang Keyword PersistentVector]
           com.mchange.v2.c3p0.ComboPooledDataSource
           honeysql.types.SqlCall
           [java.sql DatabaseMetaData ResultSet]
           [java.util Date Map]
           metabase.models.field.FieldInstance
           [metabase.query_processor.interface Field Value]))

(defprotocol ISQLDriver
  "Methods SQL-based drivers should implement in order to use `IDriverSQLDefaultsMixin`.
   Methods marked *OPTIONAL* have default implementations in `ISQLDriverDefaultsMixin`."

  (active-tables ^java.util.Set [this, ^DatabaseMetaData metadata]
    "*OPTIONAL* Return a set of maps containing information about the active tables/views, collections, or equivalent
     that currently exist in DATABASE. Each map should contain the key `:name`, which is the string name of the table.
     For databases that have a concept of schemas, this map should also include the string name of the table's
     `:schema`.

   Two different implementations are provided in this namespace: `fast-active-tables` (the default), and
   `post-filtered-active-tables`. You should be fine using the default, but refer to the documentation for those
   functions for more details on the differences.")

  ;; The following apply-* methods define how the SQL Query Processor handles given query clauses. Each method is
  ;; called when a matching clause is present in QUERY, and should return an appropriately modified version of
  ;; `HONEYSQL-FORM`. Most drivers can use the default implementations for all of these methods, but some may need to
  ;; override one or more (e.g. SQL Server needs to override the behavior of `apply-limit`, since T-SQL uses `TOP`
  ;; instead of `LIMIT`).
  (apply-source-table [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-aggregation  [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-breakout     [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-fields       [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-filter       [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-join-tables  [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-limit        [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-order-by     [this honeysql-form, ^Map query] "*OPTIONAL*.")
  (apply-page         [this honeysql-form, ^Map query] "*OPTIONAL*.")

  (column->base-type ^clojure.lang.Keyword [this, ^Keyword column-type]
    "Given a native DB column type, return the corresponding `Field` `base-type`.")

  (column->special-type ^clojure.lang.Keyword [this, ^String column-name, ^Keyword column-type]
    "*OPTIONAL*. Attempt to determine the special-type of a field given the column name and native type.
     For example, the Postgres driver can mark Postgres JSON type columns as `:type/SerializedJSON` special type.")

  (connection-details->spec [this, ^Map details-map]
    "Given a `Database` DETAILS-MAP, return a JDBC connection spec.")

  (current-datetime-fn [this]
    "*OPTIONAL*. HoneySQL form that should be used to get the current `DATETIME` (or equivalent). Defaults to
     `:%now`.")

  (date [this, ^Keyword unit, field-or-value]
    "Return a HoneySQL form for truncating a date or timestamp field or value to a given resolution, or extracting a
     date component.")

  (excluded-schemas ^java.util.Set [this]
    "*OPTIONAL*. Set of string names of schemas to skip syncing tables from.")

  (field->identifier [this, ^FieldInstance field]
    "*OPTIONAL*. Return a HoneySQL form that should be used as the identifier for FIELD.
     The default implementation returns a keyword generated by from the components returned by
     `field/qualified-name-components`. Other drivers like BigQuery need to do additional qualification, e.g. the
     dataset name as well. (At the time of this writing, this is only used by the SQL parameters implementation; in
     the future it will probably be used in more places as well.)")

  (field->alias ^String [this, ^Field field]
    "*OPTIONAL*. Return the alias that should be used to for FIELD, i.e. in an `AS` clause. The default implementation
     calls `name`, which returns the *unqualified* name of `Field`.

     Return `nil` to prevent FIELD from being aliased.")

  (quote-style ^clojure.lang.Keyword [this]
    "*OPTIONAL*. Return the quoting style that should be used by [HoneySQL](https://github.com/jkk/honeysql) when
     building a SQL statement. Defaults to `:ansi`, but other valid options are `:mysql`, `:sqlserver`, `:oracle`, and
     `:h2` (added in `metabase.util.honeysql-extensions`; like `:ansi`, but uppercases the result).

        (hsql/format ... :quoting (quote-style driver))")

  (set-timezone-sql ^String [this]
    "*OPTIONAL*. This should be a format string containing a SQL statement to be used to set the timezone for the
     current transaction. The `%s` will be replaced with a string literal for a timezone, e.g. `US/Pacific.`

       \"SET @@session.timezone = %s;\"")

  (stddev-fn ^clojure.lang.Keyword [this]
    "*OPTIONAL*. Keyword name of the SQL function that should be used to do a standard deviation aggregation. Defaults
     to `:STDDEV`.")

  (string-length-fn ^clojure.lang.Keyword [this, ^Keyword field-key]
    "Return a HoneySQL form appropriate for getting the length of a `Field` identified by fully-qualified FIELD-KEY.
     An implementation should return something like:

      (hsql/call :length (hx/cast :VARCHAR field-key))")

  (unix-timestamp->timestamp [this, field-or-value, ^Keyword seconds-or-milliseconds]
    "Return a HoneySQL form appropriate for converting a Unix timestamp integer field or value to an proper SQL
     `Timestamp`. SECONDS-OR-MILLISECONDS refers to the resolution of the int in question and with be either
     `:seconds` or `:milliseconds`."))


;; This does something important for the Crate driver, apparently (what?)
(extend-protocol jdbc/IResultSetReadColumn
  (class (object-array []))
  (result-set-read-column [x _ _] (PersistentVector/adopt x)))


(def ^:dynamic ^:private database-id->connection-pool
  "A map of our currently open connection pools, keyed by Database `:id`."
  (atom {}))

(defn- create-connection-pool
  "Create a new C3P0 `ComboPooledDataSource` for connecting to the given DATABASE."
  [{:keys [id engine details]}]
  (println "---------------- INSIDE create-connection-pool")
  (log/debug (u/format-color 'cyan "Creating new connection pool for database %d ..." id))
  (let [details-with-tunnel (ssh/include-ssh-tunnel details) ;; If the tunnel is disabled this returned unchanged
        spec (connection-details->spec (driver/engine->driver engine) details-with-tunnel)]
    (assoc (db/connection-pool (assoc spec
                                 :minimum-pool-size           1
                                 ;; prevent broken connections closed by dbs by testing them every 3 mins
                                 :idle-connection-test-period (* 3 60)
                                 ;; prevent overly large pools by condensing them when connections are idle for 15m+
                                 :excess-timeout              (* 15 60)))
      :ssh-tunnel (:tunnel-connection details-with-tunnel))))

(defn- notify-database-updated
  "We are being informed that a DATABASE has been updated, so lets shut down the connection pool (if it exists) under
   the assumption that the connection details have changed."
  [_ {:keys [id]}]
  (println "************ INSIDE notify-database-updated")
  (when-let [pool (get @database-id->connection-pool id)]
    (log/debug (u/format-color 'red "Closing connection pool for database %d ..." id))
    ;; remove the cached reference to the pool so we don't try to use it anymore
    (swap! database-id->connection-pool dissoc id)
    ;; now actively shut down the pool so that any open connections are closed
    (.close ^ComboPooledDataSource (:datasource pool))
    (when-let [ssh-tunnel (:ssh-tunnel pool)]
      (.disconnect ^com.jcraft.jsch.Session ssh-tunnel))))

(defn db->pooled-connection-spec
  "Return a JDBC connection spec that includes a cp30 `ComboPooledDataSource`.
   Theses connection pools are cached so we don't create multiple ones to the same DB."
  [{:keys [id], :as database}]

  (println "*********************************")
  (println database)
  (println "--**-- e have an existing pool for this database, so use it  --**--" (contains? @database-id->connection-pool id))
  (println "---- NOCOND ----" (get-in database [:details :impersonate]))
  (println (if (true? (get-in database [:details :impersonate])) "******* IMPERSONATED -> new-connection" "******* NOT-IMPERSONATED->check-existing"))

  (if (contains? @database-id->connection-pool id)
    ;; we have an existing pool for this database, so use it
    (if (true? (false))
      (notify-database-updated (get database :engine) database)
      (get @database-id->connection-pool id))

    ;; create a new pool and add it to our cache, then return it
    (u/prog1 (create-connection-pool database)
      (swap! database-id->connection-pool assoc id <>))))

(defn db->jdbc-connection-spec
  "Return a JDBC connection spec for DATABASE. This will have a C3P0 pool as its datasource."
  [{:keys [engine details], :as database}]
  (db->pooled-connection-spec database))

(defn handle-additional-options
  "If DETAILS contains an `:addtional-options` key, append those options to the connection string in CONNECTION-SPEC.
   (Some drivers like MySQL provide this details field to allow special behavior where needed).

   Optionally specify SEPERATOR-STYLE, which defaults to `:url` (e.g. `?a=1&b=2`). You may instead set it to
   `:semicolon`, which will separate different options with semicolons instead (e.g. `;a=1;b=2`). (While most drivers
   require the former style, some require the latter.)"
  {:arglists '([connection-spec] [connection-spec details & {:keys [seperator-style]}])}
  ;; single arity provided for cases when `connection-spec` is built by applying simple transformations to `details`
  ([connection-spec]
   (handle-additional-options connection-spec connection-spec))
  ;; two-arity+options version provided for when `connection-spec` is being built up separately from `details` source
  ([{connection-string :subname, :as connection-spec} {additional-options :additional-options, :as details} & {:keys [seperator-style]
                                                                                                               :or   {seperator-style :url}}]
   (-> (dissoc connection-spec :additional-options)
       (assoc :subname (str connection-string (when (seq additional-options)
                                                (str (case seperator-style
                                                       :semicolon ";"
                                                       :url       (if (str/includes? connection-string "?")
                                                                    "&"
                                                                    "?"))
                                                     additional-options)))))))


(defn escape-field-name
  "Escape dots in a field name so HoneySQL doesn't get confused and separate them. Returns a keyword."
  ^clojure.lang.Keyword [k]
  (keyword (hx/escape-dots (name k))))

(defn- can-connect? [driver details]
  (let [details-with-tunnel (ssh/include-ssh-tunnel details)
        connection (connection-details->spec driver details-with-tunnel)]
    (= 1 (first (vals (first (jdbc/query connection ["SELECT 1"])))))))

(defn pattern-based-column->base-type
  "Return a `column->base-type` function that matches types based on a sequence of pattern / base-type pairs."
  [pattern->type]
  (fn [_ column-type]
    (let [column-type (name column-type)]
      (loop [[[pattern base-type] & more] pattern->type]
        (cond
          (re-find pattern column-type) base-type
          (seq more)                    (recur more))))))


(defn honeysql-form->sql+args
  "Convert HONEYSQL-FORM to a vector of SQL string and params, like you'd pass to JDBC."
  {:style/indent 1}
  [driver honeysql-form]
  {:pre [(map? honeysql-form)]}
  (let [[sql & args] (try (binding [hformat/*subquery?* false]
                            (hsql/format honeysql-form
                              :quoting             (quote-style driver)
                              :allow-dashed-names? true))
                          (catch Throwable e
                            (log/error (u/format-color 'red "Invalid HoneySQL form:\n%s"
                                                       (u/pprint-to-str honeysql-form)))
                            (throw e)))]
    (into [(hx/unescape-dots sql)] args)))

(defn- qualify+escape ^clojure.lang.Keyword
  ([table]       (hx/qualify-and-escape-dots (:schema table) (:name table)))
  ([table field] (hx/qualify-and-escape-dots (:schema table) (:name table) (:name field))))


(def ^:private ^:dynamic *jdbc-options* {})

(defn- query
  "Execute a HONEYSQL-FROM query against DATABASE, DRIVER, and optionally TABLE."
  ([driver database honeysql-form]
   (jdbc/query (db->jdbc-connection-spec database)
               (honeysql-form->sql+args driver honeysql-form)
               *jdbc-options*))
  ([driver database table honeysql-form]
   (query driver database (merge {:from [(qualify+escape table)]}
                                 honeysql-form))))


(defn- table-rows-seq [driver database table]
  (query driver database table {:select [:*]}))


(defn features
  "Default implementation of `IDriver` `features` for SQL drivers."
  [driver]
  (cond-> #{:basic-aggregations
            :standard-deviation-aggregations
            :foreign-keys
            :expressions
            :expression-aggregations
            :native-parameters
            :nested-queries
            :binning
            :native-query-params}
    (set-timezone-sql driver) (conj :set-timezone)))


;;; ## Database introspection methods used by sync process

(defmacro with-metadata
  "Execute BODY with `java.sql.DatabaseMetaData` for DATABASE."
  [[binding _ database] & body]
  `(with-open [^java.sql.Connection conn# (jdbc/get-connection (db->jdbc-connection-spec ~database))]
     (let [~binding (.getMetaData conn#)]
       ~@body)))

(defmacro ^:private with-resultset-open
  "This is like `with-open` but with JDBC ResultSet objects. Will execute `body` with a `jdbc/result-set-seq` bound
  the the symbols provided in the binding form. The binding form is just like `let` or `with-open`, but yield a
  `ResultSet`. That `ResultSet` will be closed upon exit of `body`."
  [bindings & body]
  (let [binding-pairs (partition 2 bindings)
        rs-syms (repeatedly (count binding-pairs) gensym)]
    `(with-open ~(vec (interleave rs-syms (map second binding-pairs)))
       (let ~(vec (interleave (map first binding-pairs) (map #(list `~jdbc/result-set-seq %) rs-syms)))
         ~@body))))

(defn- get-tables
  "Fetch a JDBC Metadata ResultSet of tables in the DB, optionally limited to ones belonging to a given schema."
  ^ResultSet [^DatabaseMetaData metadata, ^String schema-or-nil]
  (with-resultset-open [rs-seq (.getTables metadata nil schema-or-nil "%" ; tablePattern "%" = match all tables
                                           (into-array String ["TABLE", "VIEW", "FOREIGN TABLE", "MATERIALIZED VIEW"]))]
    ;; Ensure we read all rows before exiting
    (doall rs-seq)))

(defn fast-active-tables
  "Default, fast implementation of `ISQLDriver/active-tables` best suited for DBs with lots of system tables (like
   Oracle). Fetch list of schemas, then for each one not in `excluded-schemas`, fetch its Tables, and combine the
   results.

   This is as much as 15x faster for Databases with lots of system tables than `post-filtered-active-tables` (4
   seconds vs 60)."
  [driver, ^DatabaseMetaData metadata]
  (with-resultset-open [rs-seq (.getSchemas metadata)]
    (let [all-schemas (set (map :table_schem rs-seq))
          schemas     (set/difference all-schemas (excluded-schemas driver))]
      (set (for [schema     schemas
                 table-name (mapv :table_name (get-tables metadata schema))]
             {:name   table-name
              :schema schema})))))

(defn post-filtered-active-tables
  "Alternative implementation of `ISQLDriver/active-tables` best suited for DBs with little or no support for schemas.
   Fetch *all* Tables, then filter out ones whose schema is in `excluded-schemas` Clojure-side."
  [driver, ^DatabaseMetaData metadata]
  (set (for [table (filter #(not (contains? (excluded-schemas driver) (:table_schem %)))
                           (get-tables metadata nil))]
         {:name   (:table_name table)
          :schema (:table_schem table)})))

(defn- database-type->base-type
  "Given a `database-type` (e.g. `VARCHAR`) return the mapped Metabase type (e.g. `:type/Text`)."
  [driver database-type]
  (or (column->base-type driver (keyword database-type))
      (do (log/warn (format "Don't know how to map column type '%s' to a Field base_type, falling back to :type/*."
                            database-type))
          :type/*)))

(defn- calculated-special-type
  "Get an appropriate special type for a column with `column-name` of type `database-type`."
  [driver column-name database-type]
  (when-let [special-type (column->special-type driver column-name (keyword database-type))]
    (assert (isa? special-type :type/*)
      (str "Invalid type: " special-type))
    special-type))

(defn- describe-table-fields [^DatabaseMetaData metadata, driver, {schema :schema, table-name :name}]
  (with-resultset-open [rs-seq (.getColumns metadata nil schema table-name nil)]
    (set (for [{database-type :type_name, column-name :column_name} rs-seq]
           (merge {:name          column-name
                   :database-type database-type
                   :base-type     (database-type->base-type driver database-type)}
                  (when-let [special-type (calculated-special-type driver column-name database-type)]
                    {:special-type special-type}))))))

(defn- add-table-pks
  [^DatabaseMetaData metadata, table]
  (with-resultset-open [rs-seq (.getPrimaryKeys metadata nil nil (:name table))]
    (let [pks (set (map :column_name rs-seq))]
      (update table :fields (fn [fields]
                              (set (for [field fields]
                                     (if-not (contains? pks (:name field))
                                       field
                                       (assoc field :pk? true)))))))))

(defn describe-database
  "Default implementation of `describe-database` for JDBC-based drivers. Uses various `ISQLDriver` methods and JDBC
   metadata."
  [driver database]
  (with-metadata [metadata driver database]
    {:tables (active-tables driver, ^DatabaseMetaData metadata)}))

(defn describe-table
  "Default implementation of `describe-table` for JDBC-based drivers. Uses various `ISQLDriver` methods and JDBC
   metadata."
  [driver database table]
  (with-metadata [metadata driver database]
    (->> (assoc (select-keys table [:name :schema]) :fields (describe-table-fields metadata driver table))
         ;; find PKs and mark them
         (add-table-pks metadata))))

(defn- describe-table-fks [driver database table]
  (with-metadata [metadata driver database]
    (with-resultset-open [rs-seq (.getImportedKeys metadata nil (:schema table) (:name table))]
      (set (for [result rs-seq]
             {:fk-column-name   (:fkcolumn_name result)
              :dest-table       {:name   (:pktable_name result)
                                 :schema (:pktable_schem result)}
              :dest-column-name (:pkcolumn_name result)})))))

;;; ## Native SQL parameter functions

(def PreparedStatementSubstitution
  "Represents the SQL string replace value (usually ?) and the typed parameter value"
  {:sql-string   s/Str
   :param-values [s/Any]})

(s/defn make-stmt-subs :- PreparedStatementSubstitution
  "Create a `PreparedStatementSubstitution` map for `sql-string` and the `param-seq`"
  [sql-string param-seq]
  {:sql-string   sql-string
   :param-values param-seq})

(defmulti ^{:doc          (str "Returns a `PreparedStatementSubstitution` for `x` and the given driver. "
                               "This allows driver specific parameters and SQL replacement text (usually just ?). "
                               "The param value is already prepared and ready for inlcusion in the query, such as "
                               "what's needed for SQLite and timestamps.")
            :arglists     '([driver x])
            :style/indent 1}
  ->prepared-substitution
  (fn [driver x]
    [(class driver) (class x)]))

(s/defn ^:private honeysql->prepared-stmt-subs
  "Convert X to a replacement snippet info map by passing it to HoneySQL's `format` function."
  [driver x]
  (let [[snippet & args] (hsql/format x, :quoting (quote-style driver))]
    (make-stmt-subs snippet args)))

(s/defmethod ->prepared-substitution [Object nil] :- PreparedStatementSubstitution
  [driver _]
  (honeysql->prepared-stmt-subs driver nil))

(s/defmethod ->prepared-substitution [Object Object] :- PreparedStatementSubstitution
  [driver obj]
  (honeysql->prepared-stmt-subs driver (str obj)))

(s/defmethod ->prepared-substitution [Object Number] :- PreparedStatementSubstitution
  [driver num]
  (honeysql->prepared-stmt-subs driver num))

(s/defmethod ->prepared-substitution [Object Boolean] :- PreparedStatementSubstitution
  [driver b]
  (honeysql->prepared-stmt-subs driver b))

(s/defmethod ->prepared-substitution [Object Keyword] :- PreparedStatementSubstitution
  [driver kwd]
  (honeysql->prepared-stmt-subs driver kwd))

(s/defmethod ->prepared-substitution [Object SqlCall] :- PreparedStatementSubstitution
  [driver sql-call]
  (honeysql->prepared-stmt-subs driver sql-call))

(s/defmethod ->prepared-substitution [Object Date] :- PreparedStatementSubstitution
  [driver date]
  (make-stmt-subs "?" [date]))

(defn ISQLDriverDefaultsMixin
  "Default implementations for methods in `ISQLDriver`."
  []
  (require 'metabase.driver.generic-sql.query-processor)
  {:active-tables        fast-active-tables
   ;; don't resolve the vars yet so during interactive dev if the underlying impl changes we won't have to reload all
   ;; the drivers
   :apply-source-table   (resolve 'metabase.driver.generic-sql.query-processor/apply-source-table)
   :apply-aggregation    (resolve 'metabase.driver.generic-sql.query-processor/apply-aggregation)
   :apply-breakout       (resolve 'metabase.driver.generic-sql.query-processor/apply-breakout)
   :apply-fields         (resolve 'metabase.driver.generic-sql.query-processor/apply-fields)
   :apply-filter         (resolve 'metabase.driver.generic-sql.query-processor/apply-filter)
   :apply-join-tables    (resolve 'metabase.driver.generic-sql.query-processor/apply-join-tables)
   :apply-limit          (resolve 'metabase.driver.generic-sql.query-processor/apply-limit)
   :apply-order-by       (resolve 'metabase.driver.generic-sql.query-processor/apply-order-by)
   :apply-page           (resolve 'metabase.driver.generic-sql.query-processor/apply-page)
   :column->special-type (constantly nil)
   :current-datetime-fn  (constantly :%now)
   :excluded-schemas     (constantly nil)
   :field->identifier    (u/drop-first-arg (comp (partial apply hsql/qualify) field/qualified-name-components))
   :field->alias         (u/drop-first-arg name)
   :quote-style          (constantly :ansi)
   :set-timezone-sql     (constantly nil)
   :stddev-fn            (constantly :STDDEV)})


(defn IDriverSQLDefaultsMixin
  "Default implementations of methods in `IDriver` for SQL drivers."
  []
  (require 'metabase.driver.generic-sql.query-processor)
  (merge driver/IDriverDefaultsMixin
         {:can-connect?            can-connect?
          :describe-database       describe-database
          :describe-table          describe-table
          :describe-table-fks      describe-table-fks
          :execute-query           (resolve 'metabase.driver.generic-sql.query-processor/execute-query)
          :features                features
          :mbql->native            (resolve 'metabase.driver.generic-sql.query-processor/mbql->native)
          :notify-database-updated notify-database-updated
          :table-rows-seq          table-rows-seq}))
