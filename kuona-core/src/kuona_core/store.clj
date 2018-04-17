(ns kuona-core.store
  (:require [cheshire.core :refer :all]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [kuona-core.util :refer :all]
            [slingshot.slingshot :refer :all]
            [kuona-core.stores :refer []]
            )
  (:gen-class)
  (:import (kuona_core.stores DataStore)))

(defn health
  []
  (let [response (http/get "http://localhost:9200/_cluster/health")]
    (parse-json-body response)))


(defn http-path
  [& elements]
  (clojure.string/join "/" elements))



(defn put-document
  ([metric ^DataStore store] (put-document metric store (uuid)))
  ([metric ^DataStore store id]
   (let [url (.url store [id])]
     (log/info "put-document " store id url)
     (parse-json-body (http/put url {:headers json-headers
                                     :body    (generate-string metric)})))))


(defn put-partial-document
  [^DataStore store id update]
  (let [url     (.url store '(id "_update"))
        request {:doc update}]
    (log/info "put-partial-update " store id)
    (parse-json-body (http/post url {:headers json-headers
                                     :body    (generate-string request)}))))

(defn get-count
  [^DataStore store]
  (let [url (.url store ["_count"])]
    (log/info "Reading document count for " url)
    (parse-json-body (http/get url {:headers json-headers}))))

(defn page-links
  [f & {:keys [size count]}]
  (cond
    (<= count 0) []
    (<= size 0) []
    :else
    (let [pages (/ count size)]
      (into [] (map f (range 1 (+ pages 1)))))))


(defn parse-integer
  [n]
  (cond
    (= (type n) java.lang.Long) n
    :else (try+ (. Integer parseInt n)
                (catch Object _ nil))))


(defn pagination-param
  [& {:keys [:size :page]}]
  (let [page-number (parse-integer page)]
    (cond
      (nil? page-number) (str "size=" size)
      (= page-number 1) (str "size=" size)
      :else (str "size=" size "&" "from=" (* (- page-number 1) size)))))

(defn internal-search
  [^DataStore store query]
  (log/info "internal-search" store)
  (let [url      (.url store '("_search"))
        response (http/post url {:headers json-headers :body (generate-string query)})]
    (parse-json-body response)))

(defn search
  [^DataStore store search-term size page page-fn]
  (log/info "document-search" search-term size page)
  (let [base-url   (.url store ["_search"])
        search-url (str base-url "?" "q=" search-term "&" (pagination-param :size size :page page))
        all-url    (str base-url "?" (pagination-param :size size :page page))
        url        (if (clojure.string/blank? search-term) all-url search-url)]
    (log/info "Reading document count for " url)
    (let [json-response (parse-json-body (http/get url {:headers json-headers}))
          result-count  (-> json-response :hits :total)
          documents     (map #(merge {:id (:_id %)} (:_source %)) (-> json-response :hits :hits))
          ]
      {:count (count documents)
       :items documents
       :links (page-links page-fn :size size :count result-count)})))

(defn es-error
  [e]
  {:error {:type           (-> e :error :type)
           :description    (str (-> e :error :reason) " line " (-> e :error :line) " column " (-> e :error :col))
           :query_location {:line (-> e :error :line)
                            :col  (-> e :error :col)}}})

(defn hash-child
  "Given a hash with a single key returns the value of that key"
  [h]
  (second (first h)))

(defn es-type-to-ktype
  [k value]
  (cond
    (= (-> value :type) "date") {k :timestamp}
    (= (-> value :type) "long") {k :long}
    (= (-> value :type) "keyword") {k :string}
    (= (-> value :type) "object") {k :object}
    (-> value :properties) {k :object}
    :else nil))

(defn es-mapping-to-ktypes
  "Takes an elastic search hashmap of field keys and types. Applies
  type conversion and returns a hashmap with the field names and kuona
  types"
  [h]
  (into {} (map (fn [k] (es-type-to-ktype (first k) (second k))) h)))



(defn indices
  []
  {:indices (into []
                  (map (fn [[k v]] (merge v {:name k}))
                       (:indices (parse-json-body (http/get "http://localhost:9200/_stats")))))})

(defn read-schema
  [source]
  (log/info "read-schema" source)
  (try+
    (let [id       (-> source :id)
          index    (-> source :index)
          url      (.mapping-url index)
          response (http/get url {:headers json-headers})
          body     (parse-json-body response)
          mappings (-> body hash-child hash-child hash-child hash-child)]
      {id (es-mapping-to-ktypes mappings)})
    (catch [:status 400] {:keys [request-time headers body]}
      (let [error (parse-json body)]
        (log/info "Bad request" error)
        {:error {:type        (-> error :error :type)
                 :description (str (-> error :error :reason) " : " (-> error :error :resource.id))}}))
    (catch [:status 404] {:keys [request-time headers body]}
      (let [error (parse-json body)]
        (log/info "Bad request" error)
        {:error {:type        (-> error :error :type)
                 :description (-> error :error :reason)}}))
    (catch Object _
      (log/error (:throwable &throw-context) "Unexpected error reading schema" source)
      {:error {:type        :unexpected
               :description (str (:throwable &throw-context))}})))


(defn query
  [source q]
  (try+
    (let [id       (-> source :id)
          store    (-> source :index)
          url      (.url store '("_search"))
          response (http/get url {:headers json-headers :body (generate-string q)})
          body     (parse-json-body response)
          results  (map :_source (-> body :hits :hits))
          schema   (read-schema source)]
      {:count   (-> body :hits :total)
       :results results
       :schema  schema})
    (catch [:status 400] {:keys [request-time headers body]}
      (let [error (parse-json body)]
        (log/info "Bad request" error)
        (es-error error)))
    (catch Object _ {:error {:type :unexpected :description "Unknown error"}})))


(defn find-documents
  [url]
  (log/info "find-documents" url)
  (let [json-response (parse-json-body (http/get url {:headers json-headers}))
        result-count  (-> json-response :hits :total)
        documents     (map #(merge {:id (:_id %)} (:_source %)) (-> json-response :hits :hits))]
    {:count (count documents)
     :items documents
     :links []}))

(defn get-document
  [^DataStore mapping id]
  (log/debug "getting" mapping id)
  (parse-json-body (http/get (.url mapping [id]))))

(defn has-document?
  [^DataStore store id]
  (:found (get-document store id)))

(defn all-documents
  "Retrieves all the activity based on the supplied key from the index."
  [^DataStore store]
  (log/debug "all documents in " store)
  (let [url          (.url store ["_search"] ["?size=10000"])
        query-string (generate-string {:query {:from 0 :size 10000}})
        query        {:form-params query-string}
        response     (http/get url)]
    (log/debug "all-documents response " response)
    (map #(:_source %)
         (-> response
             parse-json-body
             :hits
             :hits))))

(defn delete-document [^DataStore store id]
  (let [url (.url store [id])]
    (log/info "delete-document " store id url)
    (parse-json-body (http/delete url {:headers json-headers}))))