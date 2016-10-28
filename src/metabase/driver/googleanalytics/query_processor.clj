(ns metabase.driver.googleanalytics.query-processor
  "The Query Processor is responsible for translating the Metabase Query Language into Google Analytics request format."
  (:require (clojure [string :as s])
            [clojure.tools.logging :as log]
            [clojure.tools.reader.edn :as edn]
            [medley.core :as m]
            (metabase.query-processor [expand :as expand])
            [metabase.util :as u])
  (:import java.sql.Timestamp
           java.util.Date
           clojure.lang.PersistentArrayMap
           (metabase.query_processor.interface AgFieldRef
                                               DateTimeField
                                               DateTimeValue
                                               Field
                                               RelativeDateTimeValue
                                               Value)))

(def ^:private ^:const earliest-date "2005-01-01")
(def ^:private ^:const latest-date "today")
(def ^:private ^:const max-rows-maximum 10000)

(def ^:const ga-type->base-type
  {"STRING"      :type/Text
   "FLOAT"       :type/Float
   "INTEGER"     :type/Integer
   "PERCENT"     :type/Float
   "TIME"        :type/Float
   "CURRENCY"    :type/Float
   "US_CURRENCY" :type/Float})


;; TODO: what should this actually be?
;; https://developers.google.com/analytics/devguides/reporting/core/v3/reference#startDate
;; says: Relative dates are always relative to the current date at the time of the query and are based on the timezone of the view (profile) specified in the query.
(defn- get-timezone-id [] "UTC")

(defn- date->ga-date
  [date]
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") date))

(defprotocol ^:private IRValue
  (^:private ->rvalue [this]))

(extend-protocol IRValue
  nil                   (->rvalue [_] nil)
  Object                (->rvalue [this] this)
  Field                 (->rvalue [this] (:field-name this))
  DateTimeField         (->rvalue [this] (->rvalue (:field this)))
  Value                 (->rvalue [this] (:value this))
  DateTimeValue         (->rvalue [{{unit :unit} :field, value :value}] (date->ga-date (u/date-trunc-or-extract unit value (get-timezone-id))))
  RelativeDateTimeValue (->rvalue [{:keys [unit amount]}]
                                  (cond
                                    (and (= unit :day) (= amount 0))  "today"
                                    (and (= unit :day) (= amount -1)) "yesterday"
                                    (and (= unit :day) (< amount -1)) (str (- amount) "daysAgo")
                                    :else (date->ga-date (u/date-trunc-or-extract unit (u/relative-date unit amount) (get-timezone-id))))))

(defn- escape-map
  [chars escape-char]
  (into {} (zipmap chars (map #(str escape-char %) chars))))

(defn- escape-for-regex
  [str]
  (s/escape str (escape-map ".\\+*?[^]$(){}=!<>|:-" "\\")))

(defn- escape-for-filter-clause
  [str]
  (s/escape str (escape-map ",;\\" "\\")))

(defn- ga-filter
  [& parts]
  (escape-for-filter-clause (apply str parts)))

;;; ### source-table

(defn- handle-source-table [{{source-table-name :name} :source-table}]
  {:pre [(or (string? source-table-name)
             (keyword? source-table-name))]}
  {:ids (str "ga:" source-table-name)})

;;; ### breakout

(defn- unit->ga-dimension
  [unit]
  (case unit
    ; :minute
    :minute-of-hour   "ga:minute"
    :hour             "ga:dateHour"
    :hour-of-day      "ga:hour"
    :day              "ga:date"
    :day-of-week      "ga:dayOfWeek"
    :day-of-month     "ga:day"
    ; :day-of-year
    :week             "ga:yearWeek"
    :week-of-year     "ga:week"
    :month            "ga:yearMonth"
    :month-of-year    "ga:month"
    ; :quarter
    ; :quarter-of-year
    :year             "ga:year"))

(defn- handle-breakout [{breakout-clause :breakout}]
  {:dimensions (if breakout-clause
    (s/join "," (for [breakout-field breakout-clause]
                  (if (instance? DateTimeField breakout-field)
                    (unit->ga-dimension (:unit breakout-field))
                    (->rvalue breakout-field))))
    "")})

;;; ### filter

;; TODO: implement negate?
(defn- parse-filter-subclause:filters [{:keys [filter-type field value] :as filter} & [negate?]]
  (if negate? (throw (Exception. ":not is :not yet implemented")))
  (when-not (instance? DateTimeField field)
    (let [field (when field (->rvalue field))
          value (when value (->rvalue value))]
      (case filter-type
        :contains    (ga-filter field "=@" value)
        :starts-with (ga-filter field "=~^" (escape-for-regex value))
        :ends-with   (ga-filter field "=~"  (escape-for-regex value) "$")
        :=           (ga-filter field "==" value)
        :!=          (ga-filter field "!=" value)
        :>           (ga-filter field ">" value)
        :<           (ga-filter field "<" value)
        :>=          (ga-filter field ">=" value)
        :<=          (ga-filter field "<=" value)
        :between     (str (ga-filter field ">=" (->rvalue (:min-val filter)))
                          ";"
                          (ga-filter field "<=" (->rvalue (:max-val filter))))))))

(defn- parse-filter-clause:filters [{:keys [compound-type subclause subclauses], :as clause}]
  (case compound-type
    :and (s/join ";" (remove nil? (mapv parse-filter-clause:filters subclauses)))
    :or  (s/join "," (remove nil? (mapv parse-filter-clause:filters subclauses)))
    :not (parse-filter-subclause:filters subclause :negate)
    nil  (parse-filter-subclause:filters clause)))

(defn- handle-filter:filters [{filter-clause :filter}]
  (when filter-clause
    (let [filter (parse-filter-clause:filters filter-clause)]
      (when-not (s/blank? filter)
        {:filters filter}))))

(defn- parse-filter-subclause:interval [{:keys [filter-type field value] :as filter} & [negate?]]
  (if negate? (throw (Exception. ":not is :not yet implemented")))
  (when (instance? DateTimeField field)
    (case filter-type
      :between {:start-date (->rvalue (:min-val filter))
                :end-date   (->rvalue (:max-val filter))}
      :>       {:start-date (->rvalue (:value filter))
                :end-date   latest-date}
      :<       {:start-date earliest-date
                :end-date   (->rvalue (:value filter))}
      :=       {:start-date (->rvalue (:value filter))
                :end-date   (->rvalue (:value filter))})))

(defn- parse-filter-clause:interval [{:keys [compound-type subclause subclauses], :as clause}]
  (case compound-type
    :and (apply concat (remove nil? (mapv parse-filter-clause:interval subclauses)))
    :or  (apply concat (remove nil? (mapv parse-filter-clause:interval subclauses)))
    :not (remove nil? [(parse-filter-subclause:interval subclause :negate)])
    nil  (remove nil? [(parse-filter-subclause:interval clause)])))

(defn- handle-filter:interval [{filter-clause :filter}]
  (let [date-filters (if filter-clause (parse-filter-clause:interval filter-clause) [])]
    (case (count date-filters)
      0 {:start-date earliest-date :end-date latest-date}
      1 (first date-filters)
      (throw (Exception. "Multiple date filters are not supported")))))

;;; ### order-by

(defn- handle-order-by [{:keys [order-by] :as query}]
  (when order-by
    {:sort (s/join "," (for [{:keys [field direction]} order-by]
                         (str (case direction
                                :ascending  ""
                                :descending "-")
                              (cond
                                (instance? DateTimeField field) (unit->ga-dimension (:unit field))
                                (instance? AgFieldRef field) (get-in query [:aggregation :metric-name])
                                :else (->rvalue field)))))}))

;;; ### limit

(defn- handle-limit [{limit-clause :limit}]
  {:max-results (int (if (nil? limit-clause)
                  10000
                  limit-clause))})

(defn mbql->native
  "Transpile MBQL query into parameters required for a Google Analytics request."
  [{:keys [query] :as raw}]
  {:query (merge (handle-source-table    query)
                 (handle-breakout        query)
                 (handle-filter:interval query)
                 (handle-filter:filters  query)
                 (handle-order-by        query)
                 (handle-limit           query)
                 ;; segments and metrics are pulled out in transform-query
                 (get raw :ga)
                 ;; set to false to match behavior of other drivers
                 {:include-empty-rows false})
   :mbql? true})

(defn- builtin-metric?
  [aggregation-clause]
  (and (sequential? aggregation-clause)
       (= :metric (expand/normalize-token (get aggregation-clause 0)))
       (string? (get aggregation-clause 1))))

(defn- extract-builtin-metrics
  [aggregation-clause]
  ;; TODO: support mulitple metrics
  (if (builtin-metric? aggregation-clause)
    (get aggregation-clause 1)
    nil))

(defn- replace-builtin-metrics
  [aggregation-clause]
  (if (builtin-metric? aggregation-clause)
    ;; replace with :count as a fake aggregation
    [:count]
    nil))

(defn- builtin-segment?
  [filter-clause]
  (and (sequential? filter-clause)
       (= :segment (expand/normalize-token (get filter-clause 0)))
       (string? (get filter-clause 1))))

(defn- extract-builtin-segments
  [filter-clause]
  (let [segments (for [f filter-clause :when (builtin-segment? f)] (get f 1))]
    (cond
      (= (count segments) 1) (first segments)
      (> (count segments) 1) (throw (Exception. "Only one Google Analytics segment allowed at a time.")))))

(defn- replace-builtin-segments
  [filter-clause]
  (let [filter-clause (vec (filter (complement builtin-segment?) filter-clause))]
    (if (> (count filter-clause) 1)
      filter-clause)))

(defn transform-query [query]
  "Preprocess the incoming query to pull out builtin segments and metrics."
  (-> query
      ;; pull metrics out and put in :ga
      (assoc-in [:ga :metrics] (extract-builtin-metrics (get-in query [:query :aggregation])))
      ;; replace with :count as a fake aggregation
      (update-in [:query :aggregation] replace-builtin-metrics)
      ;; pull segments out and put in :ga
      (assoc-in [:ga :segment] (extract-builtin-segments (get-in query [:query :filter])))
      ;; remove segments from query dict
      (update-in [:query :filter] replace-builtin-segments)))

(defn- parse-date
  [format str]
  (.parse (java.text.SimpleDateFormat. format) str))

(defn- parse-number
  [str]
  (edn/read-string (s/replace str #"^0+(.+)$" "$1")))


(def ^:const ga-dimension->date-format
  {"ga:minute"    parse-number
   "ga:dateHour"  #(parse-date "yyyyMMddHH" %)
   "ga:hour"      parse-number
   "ga:date"      #(parse-date "yyyyMMdd" %)
   "ga:dayOfWeek" #(+ 1 (parse-number %))
   "ga:day"       parse-number
   "ga:yearWeek"  #(parse-date "YYYYww" %)
   "ga:week"      parse-number
   "ga:yearMonth" #(parse-date "yyyyMM" %)
   "ga:month"     parse-number
   "ga:year"      parse-number})


(defn- header->column
  [header]
  (let [date-parser (ga-dimension->date-format (.getName header))]
    (if date-parser
      {:name      (keyword "ga:date")
       :base-type :type/DateTime}
      {:name      (keyword (.getName header))
       :base-type (ga-type->base-type (.getDataType header))})))

(defn- header->getter-fn
  [header]
  (let [date-parser (ga-dimension->date-format (.getName header))
        base-type   (ga-type->base-type (.getDataType header))]
    (cond
      date-parser                   date-parser
      (isa? base-type :type/Number) edn/read-string
      :else                         identity)))

(defn execute-query
  [do-query query]
  (let [mbql?    (:mbql? (:native query))
        response (do-query query)
        columns  (map header->column (.getColumnHeaders response))
        getters  (map header->getter-fn (.getColumnHeaders response))
        columns  (if mbql?
                   ; replace last column name with :count for now since that's what our fake aggregation is
                   (conj (vec (butlast columns)) (assoc (last columns) :name :count))
                   columns)]
    {:columns  (map :name columns)
     :cols     columns
     :rows     (for [row (.getRows response)]
                 (for [[data getter] (map vector row  getters)]
                   (getter data)))
     :annotate mbql?}))
