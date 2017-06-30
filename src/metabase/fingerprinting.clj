(ns metabase.fingerprinting
  "Fingerprinting (feature extraction) for various models."
  (:require [bigml.histogram.core :as hist]
            [bigml.sketchy.hyper-loglog :as hyper-loglog]
            [clj-time.coerce :as t.coerce]
            [clj-time.core :as t]
            [clj-time.format :as t.format]
            [clj-time.periodic :as t.periodic]
            [kixi.stats.core :as stats]
            [kixi.stats.math :as math]
            [medley.core :as m]
            [metabase.db.metadata-queries :as metadata]
            [metabase.models.card :refer [Card]]
            [metabase.models.field :refer [Field]]
            [metabase.models.segment :refer [Segment]]
            [metabase.models.table :refer [Table]]
            [redux.core :as redux]
            [tide.core :as tide]))

(def ^:private ^:const percentiles (range 0 1 0.1))

(defn histogram
  "Transducer that summarizes numerical data with a histogram."
  ([] (hist/create))
  ([acc] acc)
  ([acc x] (hist/insert! acc x)))

(defn histogram-categorical
  "Transducer that summarizes categorical data with a histogram."
  ([] (hist/create))
  ([acc] acc)
  ([acc x] (hist/insert-categorical! acc (when x 1) x)))

(defn rollup
  "Transducer that groups by `groupfn` and reduces each group with `f`.
   Note the contructor airity of `f` needs to be free of side effects."
  [f groupfn]
  (let [init (f)]
    (fn
      ([] (transient {}))
      ([acc]
       (into {}
         (map (fn [[k v]]
                [k (f v)]))
         (persistent! acc)))
      ([acc x]
       (let [k (groupfn x)]
         (assoc! acc k (f (get acc k init) x)))))))

(defn safe-divide
  "Like `clojure.core//`, but returns nil if denominator is 0."
  [numerator & denominators]
  (when (or (and (not-empty denominators) (not-any? zero? denominators))
            (and (not (zero? numerator)) (empty? denominators)))
    (apply / numerator denominators)))

(defn growth
  "Relative difference between `x1` an `x2`."
  [x2 x1]
  (when (every? some? [x2 x1])
    (safe-divide (* (if (neg? x1) -1 1) (- x2 x1)) x1)))

(defn bins
  "Return centers of bins and thier frequencies of a given histogram."
  [histogram]
  (let [bins (hist/bins histogram)]
    (or (some->> bins first :target :counts (into {}))
        (into {}
          (map (juxt :mean :count))
          bins))))

(def ^{:arglists '([histogram])} nil-count
  "Return number of nil values histogram holds."
  (comp :count hist/missing-bin))

(defn total-count
  "Return total number of values histogram holds."
  [histogram]
  (+ (hist/total-count histogram)
     (nil-count histogram)))

(def ^:private ^:const ^Double cardinality-error 0.01)

(defn cardinality
  "Transducer that sketches cardinality using Hyper-LogLog."
  ([] (hyper-loglog/create cardinality-error))
  ([acc] (hyper-loglog/distinct-count acc))
  ([acc x] (hyper-loglog/insert acc x)))

(defn binned-entropy
  "Calculate entropy of given histogram."
  [histogram]
  (let [total (hist/total-count histogram)]
    (transduce (map #(let [p (/ (val %) total)]
                       (* p (math/log p))))
               (redux/post-complete + -)
               (bins histogram))))

(defn- field-type
  [field]
  (if (sequential? field)
    (mapv field-type field)
    [(:base_type field) (or (:special_type field) :type/*)]))

(def ^:private Num      [:type/Number :type/*])
(def ^:private DateTime [:type/DateTime :type/*])
(def ^:private Category [:type/* :type/Category])
(def ^:private Any      [:type/* :type/*])
(def ^:private Text     [:type/Text :type/*])

(def linear-computation? ^:private ^{:arglists '([max-cost])}
  (comp #{:linear} :computation))

(def unbounded-computation? ^:private ^{:arglists '([max-cost])}
  (comp #{:unbounded :yolo} :computation))

(def yolo-computation? ^:private ^{:arglists '([max-cost])}
  (comp #{:yolo} :computation))

(def cache-only? ^:private ^{:arglists '([max-cost])}
  (comp #{:cache} :query))

(def sample-only? ^:private ^{:arglists '([max-cost])}
  (comp #{:sample} :query))

(def full-scan? ^:private ^{:arglists '([max-cost])}
  (comp #{:full-scan :joins} :query))

(def alow-joins? ^:private ^{:arglists '([max-cost])}
  (comp #{:joins} :query))

(defmulti fingerprinter
  "Transducer that summarizes (_fingerprints_) given coll. What features are
   extracted depends on the type of corresponding `Field`(s), amount of data
   points available (some algorithms have a minimum data points requirement)
   and `max-cost.computation` setting.
   Note we are heavily using data sketches so some summary values may be
   approximate."
  #(field-type %2))

(defmethod fingerprinter Num
  [_ _]
  (redux/post-complete
   (redux/fuse {:histogram      histogram
                :cardinality    cardinality
                :kurtosis       stats/kurtosis
                :skewness       stats/skewness
                :sum            (redux/with-xform + (remove nil?))
                :sum-of-squares (redux/with-xform + (comp (remove nil?)
                                                          (map math/sq)))})
   (fn [{:keys [histogram cardinality kurtosis skewness sum sum-of-squares]}]
     (if (pos? (total-count histogram))
       (let [nil-count   (nil-count histogram)
             total-count (total-count histogram)
             unique%     (/ cardinality (max total-count 1))
             var         (or (hist/variance histogram) 0)
             sd          (math/sqrt var)
             min         (hist/minimum histogram)
             max         (hist/maximum histogram)
             mean        (hist/mean histogram)
             median      (hist/median histogram)
             range       (- max min)]
         {:histogram            (bins histogram)
          :percentiles          (apply hist/percentiles histogram percentiles)
          :sum                  sum
          :sum-of-squares       sum-of-squares
          :positive-definite?   (>= min 0)
          :%>mean               (- 1 ((hist/cdf histogram) mean))
          :cardinality-vs-count unique%
          :var>sd?              (> var sd)
          :nil-conunt           nil-count
          :has-nils?            (pos? nil-count)
          :0<=x<=1?             (<= 0 min max 1)
          :-1<=x<=1?            (<= -1 min max 1)
          :range-vs-sd          (safe-divide range sd)
          :range-vs-spread      (safe-divide range (- mean median))
          :range                range
          :cardinality          cardinality
          :min                  min
          :max                  max
          :mean                 mean
          :median               median
          :var                  var
          :sd                   sd
          :count                total-count
          :kurtosis             kurtosis
          :skewness             skewness
          :all-distinct?        (>= unique% (- 1 cardinality-error))
          :entropy              (binned-entropy histogram)
          :type                 Num})
       {:count 0}))))

(defmethod fingerprinter [Num Num]
  [_ _]
  (redux/fuse {:correlation       (stats/correlation first second)
               :covariance        (stats/covariance first second)
               :linear-regression (stats/simple-linear-regression first second)}))

(def ^:private ^:const timestamp-truncation-factor (/ 1 1000 60 60 24))

(def ^:private ^{:arglists '([t])} truncate-timestamp
  "Truncate UNIX timestamp from ms to days."
  (comp long (partial * timestamp-truncation-factor)))

(def ^:private ^{:arglists '([t])} pad-timestamp
  "Pad truncated timestamp back into a proper UNIX (ms) timestamp."
  (comp long (partial * (/ timestamp-truncation-factor))))

(defn- fill-timeseries
  "Given a coll of `[DateTime, Any]` pairs with periodicty `step` fill missing
   periods with 0."
  [step ts]
  (let [ts-index (into {} ts)]
    (into []
      (comp (map (comp truncate-timestamp t.coerce/to-long))
            (take-while (partial >= (-> ts last first)))
            (map (fn [t]
                   [t (ts-index t 0)])))
      (some-> ts
              ffirst
              pad-timestamp
              t.coerce/from-long
              (t.periodic/periodic-seq step)))))

(defn- decompose-timeseries
  "Decompose given timeseries with expected periodicty `resolution` into trend,
   seasonal component, and reminder.
   `resolution` can be one of `:day`, or `:month`."
  [resolution ts]
  (let [period (case resolution
                 :month 12
                 :day 52)]
    (when (>= (count ts) (* 2 period))
      (tide/decompose period ts))))

(defmethod fingerprinter [DateTime Num]
  [{:keys [max-cost resolution]} _]
  (let [resolution (or resolution :raw)]
    (redux/post-complete
     (redux/pre-step
      (redux/fuse {:linear-regression (stats/simple-linear-regression first second)
                   :series            (if (= resolution :raw)
                                        conj
                                        (redux/post-complete
                                         conj
                                         (partial fill-timeseries
                                                  (case resolution
                                                    :month (t/months 1)
                                                    :day (t/days 1)))))})
      (fn [[x y]]
        [(-> x t.format/parse t.coerce/to-long truncate-timestamp) y]))
     (fn [{:keys [series linear-regression]}]
       (let [ys-r (->> series (map second) reverse not-empty)
             {:keys [trend seasonal reminder]}
             (when (and (not= resolution :raw)
                        (unbounded-computation? max-cost))
               (decompose-timeseries resolution series))]
         (merge {:series            (for [[x y] series]
                                      [(t.coerce/from-long (pad-timestamp x)) y])
                 :linear-regression linear-regression
                 :trend             trend
                 :seasonal          seasonal
                 :reminder          reminder}
                (case resolution
                  :month {:YoY          (growth (first ys-r) (nth ys-r 11))
                          :YoY-previous (growth (second ys-r) (nth ys-r 12))
                          :MoM          (growth (first ys-r) (second ys-r))
                          :MoM-previous (growth (second ys-r) (nth ys-r 2))}
                  :day   {:DoD          (growth (first ys-r) (second ys-r))
                          :DoD-previous (growth (second ys-r) (nth ys-r 2))}
                  :raw   nil)))))))

(defmethod fingerprinter [Category Any]
  [opts [x y]]
  (rollup (redux/pre-step (fingerprinter opts y) second) first))

(defmethod fingerprinter Text
  [_ _]
  (redux/post-complete
   (redux/fuse {:histogram (redux/pre-step histogram (stats/somef count))})
   (fn [{:keys [histogram]}]
     (let [nil-count (nil-count histogram)]
       {:min        (hist/minimum histogram)
        :max        (hist/maximum histogram)
        :histogram  (bins histogram)
        :count      (total-count histogram)
        :nil-conunt nil-count
        :has-nils?  (pos? nil-count)
        :type       Text}))))

(defn- quarter
  [dt]
  (-> (t/month dt) (/ 3) Math/ceil long))

(defmethod fingerprinter DateTime
  [_ _]
  (redux/post-complete
   (redux/pre-step
    (redux/fuse {:histogram         (redux/pre-step histogram t.coerce/to-long)
                 :histogram-hour    (redux/pre-step histogram-categorical
                                                    (stats/somef t/hour))
                 :histogram-day     (redux/pre-step histogram-categorical
                                                    (stats/somef t/day-of-week))
                 :histogram-month   (redux/pre-step histogram-categorical
                                                    (stats/somef t/month))
                 :histogram-quarter (redux/pre-step histogram-categorical
                                                    (stats/somef quarter))})
    t.format/parse)
   (fn [{:keys [histogram histogram-hour histogram-day histogram-month
                histogram-quarter]}]
     (let [nil-count (nil-count histogram)
           ->datetime (comp t.coerce/from-long long)]
       {:min               (->datetime (hist/minimum histogram))
        :max               (->datetime (hist/maximum histogram))
        :histogram         (m/map-keys ->datetime (bins histogram))
        :percentiles       (m/map-vals ->datetime
                                       (apply hist/percentiles histogram
                                              percentiles))
        :histogram-hour    (bins histogram-hour)
        :histogram-day     (bins histogram-day)
        :histogram-month   (bins histogram-month)
        :histogram-quarter (bins histogram-quarter)
        :count             (total-count histogram)
        :nil-conunt        nil-count
        :has-nils?         (pos? nil-count)
        :entropy           (binned-entropy histogram)
        :type              DateTime}))))

(defmethod fingerprinter Category
  [_ _]
  (redux/post-complete
   (redux/fuse {:histogram   histogram-categorical
                :cardinality cardinality})
   (fn [{:keys [histogram cardinality]}]
     (let [nil-count   (nil-count histogram)
           total-count (total-count histogram)
           unique%     (/ cardinality (max total-count 1))]
       {:histogram            (bins histogram)
        :cardinality-vs-count unique%
        :nil-conunt           nil-count
        :has-nils?            (pos? nil-count)
        :cardinality          cardinality
        :count                total-count
        :all-distinct?        (>= unique% (- 1 cardinality-error))
        :entropy              (binned-entropy histogram)
        :type                 Category}))))

(defmethod fingerprinter :default
  [_ field]
  (redux/post-complete
   (redux/fuse {:total-count stats/count
                :nil-count   (redux/with-xform stats/count (filter nil?))})
   (fn [{:keys [total-count nil-count]}]
     {:count       total-count
      :nil-count   nil-count
      :has-nils?   (pos? nil-count)
      :type        nil
      :actual-type (field-type field)})))

(prefer-method fingerprinter Category Text)
(prefer-method fingerprinter Num Category)

(defn- fingerprint-field
  "Transduce given column with corresponding fingerprinter."
  [opts field data]
  (transduce identity (fingerprinter opts field) data))

(defn- fingerprint-query
  "Transuce each column in given dataset with corresponding fingerprinter."
  [opts {:keys [rows cols]}]
  (transduce identity
             (redux/fuse
              (into {}
                (map-indexed (fn [i field]
                               [(:name field)
                                (redux/pre-step (fingerprinter opts field)
                                                #(nth % i))]))
                cols))
             rows))

(def ^:private ^:const ^Long max-sample-size 10000)

(defmulti fingerprint
  "Given a model, fetch corresponding dataset and compute its fingerprint.

   Takes a map of options as first argument. Recognized options:
   * `:max-cost`         a map with keys `:computation` and `:query` which
                         limits maximal resource expenditure when computing the
                         fingerprint. `:computation` can be one of `:linear`
                         (O(n) or better), `:unbounded`, or `:yolo` (full blown
                         machine learning etc.). `query` can be one of
                         `:cache` (use only cached data), `:sample` (sample
                         up to `max-sample-size` rows), `:full-scan` (full table
                         scan), or `:joins` (bring in data from other tables if
                         needed).

   * `:resolution`       controls pre-aggregation by time. Can be one of `:day`,
                        `:month`, or `:raw`"
  #(class %2))

(defn- extract-query-opts
  [{:keys [max-cost]}]
  (cond-> {}
    (sample-only? max-cost) (assoc :limit max-sample-size)))

(defmethod fingerprint (class Field)
  [opts field]
  (let [data (metadata/field-values field (extract-query-opts opts))]
    (-> (fingerprint-field opts field data)
        (assoc :field field))))

(defmethod fingerprint (class Table)
  [opts table]
  (fingerprint-query opts (metadata/query-values
                           (:db_id table)
                           (merge (extract-query-opts opts)
                                  {:source-table (:id table)}))))

(defmethod fingerprint (class Card)
  [opts card]
  (fingerprint-query opts (metadata/query-values
                           (:database_id card)
                           (merge (extract-query-opts opts)
                                  (-> card :dataset_query :query)))))

(defmethod fingerprint (class Segment)
  [opts segment]
  (fingerprint-query opts (metadata/query-values
                           (metadata/db-id segment)
                           (merge (extract-query-opts opts)
                                  (:definition segment)))))

(defn compare-fingerprints
  "Compare fingerprints of two models."
  [opts a b]
  {(:name a) (fingerprint opts a)
   (:name b) (fingerprint opts b)})

(defn multifield-fingerprint
  "Holistically fingerprint dataset with multiple columns.
   Takes and additional option `:resolution` which controls how timeseries data
   is aggregated. Possible values: `:month`, `:day`, `:raw`."
  [{:keys [resolution] :as opts} a b]
  (assert (= (:table_id a) (:table_id b)))
  {:fingerprint (->> (metadata/query-values
                      (metadata/db-id a)
                      (merge
                       (extract-query-opts opts)
                       (if (and (isa? (field-type a) DateTime)
                                (isa? (field-type b) Num)
                                (not= resolution :raw))
                         {:source-table (:table_id a)
                          :breakout     [[:datetime-field [:field-id (:id a)]
                                          resolution]]
                          :aggregation  [:sum [:field-id (:id b)]]}
                         {:source-table (:table_id a)
                          :fields       [[:field-id (:id a)]
                                         [:field-id (:id b)]]})))
                     :rows
                     (fingerprint-field opts [a b]))
   :fields      (compare-fingerprints opts a b)})