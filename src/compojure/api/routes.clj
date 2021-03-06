(ns compojure.api.routes
  (:require [compojure.core :refer :all]
            [clojure.string :as string]
            [cheshire.core :as json]
            [ring.swagger.swagger2 :as rss]
            [compojure.api.middleware :as mw]))

(defn ->path [s params]
  (->> s
       (re-seq #"(.*?):(.[^:|(/]*)([/]?)")
       (map (comp vec rest))
       (map #(update-in % [1] keyword))
       flatten
       (map (fn [token]
              (if (keyword? token)
                (string/replace
                  (json/generate-string
                    (or (token params)
                        (throw
                          (IllegalArgumentException.
                            (str "Missing path-parameter "
                                 token " for path " s)))))
                  #"^\"(.+(?=\"$))\"$"
                  "$1")
                token)))
       (apply str)))

(defn- duplicates [seq]
  (for [[id freq] (frequencies seq)
        :when (> freq 1)] id))

(defn- route-lookup-table [routes]
  (let [entries (for [[path endpoints] (:paths routes)
                      [method {:keys [x-name parameters]}] endpoints
                      :let [params (:path parameters)]
                      :when x-name]
                  [x-name {path (merge
                                  {:method method}
                                  (if params
                                    {:params params}))}])
        route-names (map first entries)
        duplicate-route-names (duplicates route-names)]
    (when (seq duplicate-route-names)
      (throw (IllegalArgumentException.
               (str "Found multiple routes with same name: "
                    (string/join "," duplicate-route-names)))))
    (into {} entries)))

;;
;; Endpoint Trasformers
;;

(defn strip-no-doc-endpoints
  "Endpoint transformer, strips all endpoints that have :x-no-doc true."
  [endpoint]
  (if-not (some-> endpoint :x-no-doc true?)
    endpoint))

(defn non-nil-routes
  [endpoint]
  (or endpoint {}))

;;
;; Public API
;;

(defmulti collect-routes identity)

(defmacro api-root [& body]
  (let [[all-routes body] (collect-routes body)
        lookup (route-lookup-table all-routes)
        documented-routes (->> all-routes
                               (rss/transform-paths non-nil-routes)
                               (rss/transform-paths strip-no-doc-endpoints))]
    `(with-meta (routes ~@body) {:routes '~documented-routes
                                 :lookup ~lookup})))

(defn path-for*
  "Extracts the lookup-table from request and finds a route by name."
  [route-name request & [params]]
  (let [[path details] (some-> request
                               mw/get-options
                               :lookup
                               route-name
                               first)
        path-params (:params details)]
    (if (seq path-params)
      (->path path params)
      path)))

(defmacro path-for
  "Extracts the lookup-table from request and finds a route by name."
  [route-name & [params]]
  `(path-for* ~route-name ~'+compojure-api-request+ ~params))
