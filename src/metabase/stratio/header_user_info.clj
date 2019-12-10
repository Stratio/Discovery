(ns metabase.stratio.header-user-info
  (:require [buddy.sign.jwt :as jwt]
            [buddy.core.keys :as keys]
            [buddy.core.codecs :as codecs]
            [buddy.core.codecs.base64 :as b64]
            [clj-http.client :as http]
            [clj-time.core :as time]
            [clojure.string :as str]
            [cheshire.core :as json]
            [metabase.config :as config]
            [metabase.public-settings :as public-settings]
            [clojure.tools.logging :as log]))

(defn- split-token
  [token]
  (str/split token #"\." 3))


(defn- parse-data
  [^String data]
  (-> (b64/decode data)
      (codecs/bytes->str)
      (json/parse-string true)))


(defn- parse-header
  [token]
  (try
    (let [[header-b64] (split-token token)
          header       (parse-data header-b64)
          alg          (:alg header)]
      (cond-> header
        alg (assoc :alg (keyword (str/lower-case alg)))))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (throw (ex-info "Message seems corrupt or manipulated."
               {:type :validation :cause :header})))))


(defn- get-alg
  [token]
  (:alg (parse-header token)))


(defn- http-header->jwt-token
  [headers]
  (let [header-name       (config/config-str :jwt-header-name)
        header-name-lower (str/lower-case header-name)]
    (cond
      (contains? headers header-name)       (get headers header-name)
      (contains? headers header-name-lower) (get headers header-name-lower)
      :else                                 (throw (Exception. "Could not find Authorization header")))))

(defn- verify-token
  [token pkey]
  (let [alg (get-alg token)]
    (jwt/unsign token pkey {:alg alg})))


(defn- ssl-config []
  (if (config/config-bool :jwt-insecure-request-pkey)
    {:insecure? true}
    {:trust-store (config/config-str :mb-jetty-ssl-truststore)
     :trust-store-pass (config/config-str :mb-jetty-ssl-truststore-password)}))

(defn- get-verification-key
  [url]
  (-> url
      (http/get (ssl-config))
      (:body)
      (keys/str->public-key)))


(defn- http-headers->user-info-jwt
  "Gets user info map {:user username :groups [group1 ... groupN]} from jwt token in headers
  or map with :error key if some error happens"
  [headers]
  (log/debug "Getting user info from JWT token")
  (try
    (let [username-claim (config/config-kw :jwt-username-claim)
          groups-claim   (config/config-kw :jwt-groups-claim)
          token          (http-header->jwt-token headers)
          pkey           (get-verification-key (config/config-str :jwt-public-key-endpoint))]
      (cond
        (not token) {:error "Could not obtain jwt token from request headers"}
        (not pkey) {:error "Could not obtain verification key for jwt token"}
        pkey (let [info (-> token
                            (verify-token pkey)
                            (select-keys [username-claim groups-claim])
                            (update-in [groups-claim] #(if (empty? %) [] (str/split % #","))))]
               {:user (username-claim info) :groups (groups-claim info)})))
    (catch Exception e
      {:error (.toString e)})))


(defn- http-headers->user-info-headers
  "Gets user info map {:user username :groups [group1 ... groupN]} from user/groups http headers
  or map with :error key if some error happens"
  [headers]
  (log/debug "Getting user info from user/group HTTP headers")
  (try
    (let [user-name  (get headers (public-settings/user-header))
          groups-str (get headers (public-settings/group-header) "")
          groups     (str/split groups-str (re-pattern (public-settings/group-header-delimiter)))]
      {:user user-name :groups groups})
    (catch Exception e
      {:error (.toString e)})))


(defn http-headers->user-info
  "Gets user info map {:user username :groups [group1 ... groupN]} either form jwt token
  or headers depending on config; or map with :error key if some error happens"
  [headers]
  (if (config/config-bool :use-jwt-authentication)
    (http-headers->user-info-jwt     headers)
    (http-headers->user-info-headers headers)))
