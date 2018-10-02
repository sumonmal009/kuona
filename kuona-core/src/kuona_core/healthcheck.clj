(ns kuona-core.healthcheck
  (:require [kuona-core.http :as http]))

(defn filter-actuator-links
  "Filters the list of actuator links for health and info references"
  [links]
  (merge {} (-> links
                :_links
                (select-keys [:info :health])))
  )
(defn merge-list
  "Merge the maps in the list into a single map"
  [list]
  (if (first list)
    (merge (first list) (merge-list (rest list)))
    {}))

(defn read-link
  "Takes a map containing an :href key. Retrieves the JSON response from the supplied href and returns the value. Returns {} if the key does not exist"
  [link]
  (if (:href link)
    (http/json-get (:href link))
    {}))

(defn spring-actuator-health
  "Given a Spring actuator endpoint returns the health and info endpoints as a single map."
  [url]
  (let [actuator-links (http/json-get url)
        links          (filter-actuator-links actuator-links)]
    (merge-list (map read-link (vals links)))))
