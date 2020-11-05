(defproject metabase/crossdata-driver "1.0.0-SNAPSHOT"
  :min-lein-version "2.5.0"

  :dependencies
  [[com.stratio.crossdata.driver/stratio-crossdata-jdbc4 "2.19.0-809dcaf"
    :exclusions [com.fasterxml.jackson.core/jackson-core]]]

  :repositories [["stratio" "https://niquel.int.stratio.com:1443/repository/public"]]

  :profiles
  {:provided
   {:dependencies [[metabase-core "1.0.0-SNAPSHOT"]]}

   :uberjar
   {:auto-clean    true
    :aot           :all
    :javac-options ["-target" "1.8", "-source" "1.8"]
    :target-path   "target/%s"
    :uberjar-name  "crossdata.metabase-driver.jar"}})
