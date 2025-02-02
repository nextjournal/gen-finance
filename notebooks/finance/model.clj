(ns finance.model
  {:nextjournal.clerk/toc true
   :nextjournal.clerk/eval :sci}
  (:require [clojure.set :as set]
            [emmy.clerk :as ec]
            [emmy.leva :as leva]
            [emmy.viewer :as ev]
            [gen.choicemap :as choicemap]
            [gen.distribution.kixi :as kixi]
            [gen.dynamic :as dynamic :refer [gen]]
            [gen.inference.importance :as importance]
            [gen.trace :as trace]
            [nextjournal.clerk :as clerk]))

{::clerk/visibility {:code :hide :result :hide}}

(ec/install!)

(defn deep-merge-with
  "Like merge-with, but merges maps recursively, applying the given fn
  only when there's a non-map at a particular level.
  (deep-merge-with + {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
                     {:a {:b {:c 2 :d {:z 9} :z 3} :e 100}})
  -> {:a {:b {:z 3, :c 3, :d {:z 9, :x 1, :y 2}}, :e 103}, :f 4}"
  [f & maps]
  ;; Source: https://clojure.github.io/clojure-contrib/map-utils-api.html#clojure.contrib.map-utils/deep-merge-with
  (apply (fn m [& maps]
           (if (every? map? maps)
             (apply merge-with m maps)
             (apply f maps)))
         maps))

(defn deep-merge
  [& maps]
  (apply deep-merge-with merge maps))

(defn round [^double x]
  (Math/round x))

(defn domain
  [k data]
  (let [values (map #(get % k)
                    data)]
    [(apply min values)
     (apply max values)]))

(defn revenue+cost-schema
  [data]
  {:schema "https://vega.github.io/schema/vega-lite/v5.json"
   :embed/opts {:actions false}
   :data {:values data}
   :width 600 :height 300
   :repeat {:layer [:revenue :total-cost]}
   :spec {:mark {:type :area
                 :line {:strokeWidth 4}
                 :fillOpacity 0.25}
          :encoding {:x {:field :period
                         :type "quantitative"
                         :scale {:domain (domain :period data)}}
                     :y {:title "US dollars"
                         :field {:repeat :layer}
                         :type "quantitative"}
                     :color {:datum {:repeat :layer}
                             :type :nominal}}}})

(def revenue+cost-chart
  (comp clerk/vl revenue+cost-schema))

(defn format-money [value]
  (str (if (neg? value)
         "–$"
         "$")
       (format "%.2f"
               (double
                (Math/abs ^double value)))))

(defn ->table [sim]
  (clerk/table
   (map (fn [m]
          (reduce (fn [m k] (update m k format-money))
                  m
                  [:ad-spend :cost-of-service :profit-per-user
                   :revenue :total-cost]))
        sim)))

(defn cpa
  "Cost per user acquisition."
  [cost users]
  (double
   (/ cost users)))

(defn total-value [simulation]
  (reduce (fn [value {:keys [revenue total-cost]}]
            (+ value (- revenue total-cost)))
          0.0
          simulation))

;; TODO first version - teach them what the simulator is, and what they're
;; learning as they see multiple simulations at once. (this is sliders attached
;; to simulate-business, basically what we ahve now.)
;;
;; TODO next thing - inference. what if you observed some data about the
;; business? can you use the business model to work backwards and infer the
;; slider values?
;;
;; - show some data, infer slider values NOTE how?

;;
;; TODO is there a version of this business that has value more than something?
;;
;; - constrain on total value > 10000.
;;
;; 3 pages in the thing.

(defn sim->choicemaps
  "TODO does this need to be more efficient? does it matter?"
  [sim]
  (into {}
        (map (fn [{:keys [period] :as record}]
               {period record})
             sim)))

(def record-row
  (gen [row wiggle]
    (persistent!
     (reduce-kv
      (fn [acc k v]
        (if (= k :period)
          (assoc! acc k v)
          (assoc! acc k (dynamic/trace! k kixi/normal v wiggle))))
      (transient {})
      row))))

(def simulate-business
  (gen [{:keys [users cost] :or {users 0 cost 0}}
        {:keys [periods
                retention-rate
                cost-of-service
                revenue-per-paying
                free->pay
                ad-spend-increase
                viral-growth-kicker
                wiggle]
         :or   {retention-rate      0.8
                periods             20
                cost-of-service     0
                revenue-per-paying  0
                free->pay           0
                ad-spend-increase   0
                viral-growth-kicker 0
                wiggle              0.01}}]
    (let [pay-rate        (/ free->pay 100)
          paying          (round (* pay-rate users))
          revenue         (* revenue-per-paying paying)
          init-cpa        (cpa cost users)
          spend-rate      (inc (/ ad-spend-increase 100.0))
          cost-per-user   cost-of-service
          cost-of-service (* cost-per-user users)
          initial-period  (dynamic/trace!
                           0
                           record-row
                           {:period                  0
                            :ad-spend                cost
                            :total-new-users         users
                            :virally-acquired-users  0
                            :bought-users            users
                            :paying-users            paying
                            :revenue                 revenue
                            :cost-of-service         cost-of-service
                            :total-cost              (+ cost cost-of-service)
                            :profit-per-user         (/ (- revenue cost cost-of-service)
                                                        paying)
                            :cumulative-users        users
                            :cumulative-paying-users paying
                            :cpa                     (cpa cost users)}
                           wiggle)
          next-period     (fn [prev]
                            (let [period           (inc (:period prev))
                                  new-spend        (* spend-rate (:ad-spend prev))
                                  viral-users      (round (* viral-growth-kicker (:total-new-users prev)))
                                  bought-users     (round (/ new-spend init-cpa))
                                  total-new-users  (+ bought-users viral-users)
                                  new-paying-users (round (* pay-rate total-new-users))
                                  users-sum        (round (+ (* retention-rate
                                                                (:cumulative-users prev))
                                                             total-new-users))
                                  paying-sum       (round (+ (* retention-rate
                                                                (:cumulative-paying-users prev))
                                                             new-paying-users))
                                  cost-of-service  (* cost-per-user users-sum)
                                  total-cost       (+ new-spend cost-of-service)
                                  revenue          (* revenue-per-paying paying-sum)]
                              (dynamic/trace!
                               period
                               record-row
                               {:period                  period
                                :ad-spend                new-spend
                                :total-new-users         total-new-users
                                :virally-acquired-users  viral-users
                                :bought-users            bought-users
                                :paying-users            new-paying-users
                                :revenue                 revenue
                                :cost-of-service         cost-of-service
                                :total-cost              total-cost
                                :profit-per-user         (/ (- revenue total-cost)
                                                            new-paying-users)
                                :cumulative-users        users-sum
                                :cumulative-paying-users paying-sum
                                :cpa                     (cpa new-spend total-new-users)}
                               wiggle)))]
      (->> (iterate next-period initial-period)
           (take periods)
           (into [])))))


(def create-business
  (gen [initial-data {:keys [value-target periods]}]
    (let [free->pay           (dynamic/trace! :free->pay kixi/uniform 0 100)
          ad-spend-increase   (dynamic/trace! :ad-spend-increase kixi/uniform 0 100)
          viral-growth-kicker (dynamic/trace! :viral-growth-kicker kixi/uniform 0 2)
          retention-rate      (dynamic/trace! :retention-rate kixi/uniform 0 1)
          cost-of-service     (dynamic/trace! :cost-of-service kixi/uniform 0 80)
          revenue-per-paying  (dynamic/trace! :revenue-per-paying kixi/uniform 0 80)
          simulation          (dynamic/trace!
                               :simulation
                               simulate-business
                               initial-data
                               {:periods periods
                                :free->pay           free->pay
                                :ad-spend-increase   ad-spend-increase
                                :viral-growth-kicker viral-growth-kicker
                                :retention-rate      retention-rate
                                :cost-of-service     cost-of-service
                                :revenue-per-paying  revenue-per-paying})
          value           (total-value simulation)]
      (dynamic/trace! :total-value kixi/normal value 0.001)
      (when value-target
        (dynamic/trace! :profitable?
                        kixi/bernoulli
                        (if (> value value-target)
                          1.0
                          0.0)))
      simulation)))

(defn value-histogram [data]
  {:schema "https://vega.github.io/schema/vega-lite/v5.json"
   :embed/opts {:actions false}
   :width 600 :height 300
   :data {:values data}
   :mark {:type :bar :color :blue}
   :encoding {:x {:bin true :field :total-value}
              :y {:aggregate "count"}}})

{::clerk/visibility {:code :show :result :show}}

;; ## Simulating a Business

;; `initial-data` contains entries for
;;
;; - `:users`: the starting number of users and
;; - `:cost`: the total amount of advertising spend allocated per time period.

(def initial-data
  {:users 5130
   :cost 33300.0})

;; This configuration holds the initial parameters of the simulation we'll run
;; below.
;;
;; Notice that each entry maps to a slider in the hovering control panel.
;;
;; Simulation parameters:
;;
;; - `:trials`: total number of simulations to generate
;; - `:periods`: number of time periods to simulate the business
;; - `:value-target`: the goal business value, used for importance sampling.
;; - `:n-samples`: number of samples used in importance sampling

;; Business configuration:
;;
;; - `:free->pay`: percentage of users that convert from free => paid each time period
;; - `:ad-spend-increase`: percentage increase in ad spending per period
;; - `:viral-growth-kicker`: ratio of new viral users to last time period's total new users
;; - `:retention-rate`: average user retention rate, constant per simulation
;; - `:cost-of-service`: total cost to serve a user
;; - `:revenue-per-paying`: revenue per paying customer per time period

(def config
  {;; Simulation parameters
   :trials              10
   :periods             20
   :value-target        10000
   :n-samples           10

   ;; Business configuration
   :free->pay           10
   :ad-spend-increase   5
   :viral-growth-kicker 0.75
   :retention-rate      0.8
   :cost-of-service     0.1
   :revenue-per-paying  6.446

   ;; Inference configuration
   :prefix              50
   :wiggle              0.01})

;; `simulate-business` lets us simulate the history of a business with all of
;; the assumptions from `config`:

(def simulation
  (simulate-business initial-data config))

;; Let's look at the first 10 time periods of data in table form:

(->table (take 10 simulation))

;; Next, let's look at a chart of total revenue (in blue) and total costs (in
;; red) over time. For the business to be viable, the blue line needs to cross
;; above the red line.

(revenue+cost-chart simulation)

;; The **total value** of the business is the sum of `(- revenue cost)` for all
;; time periods. This particular business is worth the following:

(total-value simulation)

;; ## Interactive Simulation
;;
;; The following charts are tied to the slider hovering over the page. Play with
;; the sliders and watch the charts update.
;;
;; The first chart shows the revenue+cost chart from above, but tied to the
;; sliders.
;;
;; Then, the table from above.

(def business-config
  {:retention-rate      {:min 0 :max 1 :step 0.01}
   :free->pay           {:min 0 :max 100 :step 0.01}
   :ad-spend-increase   {:min 0 :max 100 :step 0.01}
   :viral-growth-kicker {:min 0 :max 1 :step 0.01}
   :cost-of-service     {:min 0 :max 10 :step 0.01}
   :revenue-per-paying  {:min 0 :max 70 :step 0.01}})

(def schema
  {"Simulation Params"
   (leva/folder
    {:periods {:min 1 :max 50 :step 1}}
    {:order -1})

   "Business Config"
   (leva/folder business-config)

   "Inference"
   (leva/folder
    {:value-target {:min 0 :max 10000000 :step 1000}
     :wiggle       {:min 0 :max 5 :step 0.01}
     :n-samples    {:min 0 :max 100 :step 5}
     :trials       {:min 0 :max 1000 :step 5}
     :prefix       {:min 0 :max 100 :step 10}})})

^{::clerk/visibility {:code :hide}}
(ev/with-let [!state config]
  [:<>
   (leva/controls
    {:atom   !state
     :schema schema})
   (list 'let ['config {:initial-data `initial-data
                        :config       (list 'deref !state)}
               'sim   (list `simulate-business
                            `initial-data
                            (list 'deref !state))]
         [:<>
          ['nextjournal.clerk.render/render-vega-lite
           (list `revenue+cost-schema 'sim)]
          ['nextjournal.clerk.render/inspect
           (list `->table 'sim)]])])

;; ## Inference

{::clerk/visibility {:code :hide :result :hide}}

(def fields-to-infer
  #{;; :ad-spend-increase
    :revenue-per-paying
    :retention-rate
    ;; :viral-growth-kicker
    ;; :cost-of-service
    :free->pay})

(defn do-inference
  [{:keys [initial-data config sim]}]
  (let [{:keys [prefix periods trials n-samples value-target]} config
        prefix (take (Math/floor (* periods (/ prefix 100)))
                     sim)
        constraints (merge (select-keys config (set/difference (set (keys config))
                                                               fields-to-infer))
                           {:simulation (sim->choicemaps prefix)
                            ;; :profitable? true
                            })
        infer (fn []
                (-> (importance/resampling
                     create-business
                     [initial-data config]
                     constraints
                     n-samples)
                    (:trace)
                    (trace/get-choices)
                    ;; TODO remove this to get the full set of values.
                    #_(choicemap/get-values-shallow)
                    (choicemap/->map)))]
    (repeatedly trials infer)))

(defn infer-histogram [field min max data]
  {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
   :data {:values data}
   :mark {:type "bar"
          :clip true}
   :encoding {:x {:bin true
                  :field field
                  :scale {:domain [min max]}}
              :y {:aggregate "count"}}})

(defn infer-strip-plot [field min max data]
  {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
   :embed/opts {:actions false}
   :data {:values data}
   :mark {:type "tick"
          :clip true}
   :encoding {:x {:field field
                  :type "quantitative"
                  :scale {:domain [min max]}}}})

(defn infer-union-plot [field min max data]
  {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
   :embed/opts {:actions false}
   :data {:values data}
   :spacing 10
   :bounds :flush
   :vconcat [(deep-merge (infer-histogram field min max data)
                         {:encoding {:x {:axis nil}}})
             (infer-strip-plot field min max data)]})

(defn projection-plot
  [field inf]
  (let [datas (map (fn [business]
                     (mapv (fn [[period m]]
                             (assoc m :period period))
                           (:simulation business)))
                   inf)]
    {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
     :embed/opts {:actions false}
     :width 600
     :height 300
     :layer (for [data datas]
              {:data {:values data}
               :mark {:type :line
                      :strokeOpacity 0.25}
               :encoding {:x {:field :period
                              :type :quantitative
                              :scale {:domain (domain :period data)}}
                          :y {:field field
                              :type :quantitative}}})}))

{::clerk/visibility {:code :hide :result :show}}

(ev/with-let [!state config]
  [:<>
   (leva/controls
    {:atom !state :schema schema})
   (list 'let ['sim   (list `simulate-business
                            `initial-data
                            (list 'deref !state))
               'inf (list `do-inference
                          {:initial-data `initial-data
                           :config       (list 'deref !state)
                           :sim          'sim})]
         [:<>
          ['nextjournal.clerk.render/inspect
           (list `->table 'sim)]

          (into [:<> [:h2 "Inferred parameters"]]
                (map (fn [[field {:keys [min max]}]]
                       ['nextjournal.clerk.render/render-vega-lite
                        (list `infer-union-plot field min max 'inf)])
                     (select-keys business-config fields-to-infer)))

          (into [:<> [:h2 "Inferred fields"]]
                (for [field #{:revenue
                              :cumulative-paying-users
                              :total-cost
                              :ad-spend
                              :cumulative-users
                              :virally-acquired-users
                              :bought-users
                              :profit-per-user
                              :total-new-users
                              :cost-of-service
                              :cpa
                              :paying-users}]
                  ['nextjournal.clerk.render/render-vega-lite
                   (list `projection-plot field 'inf)]))])])
