(ns kuona-collector.maven
  (:require [clojure.java.shell :as shell]
            [cheshire.core :refer :all]
            [clojure.tools.logging :as log]
            [clojure.data.xml :refer :all]
            [kuona-collector.util :refer :all])
  (:gen-class))

(defn load-pom-file
  [path]
  (let [input-xml (clojure.java.io/reader path)]
    (parse input-xml)))