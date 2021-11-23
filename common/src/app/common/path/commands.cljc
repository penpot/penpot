;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.path.commands
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]))

(defn command->point
  ([prev-pos {:keys [relative params] :as command}]
   (let [{:keys [x y] :or {x (:x prev-pos) y (:y prev-pos)}} params]
     (if relative
       (-> prev-pos (update :x + x) (update :y + y))
       (command->point command))))

  ([command]
   (when-not (nil? command)
     (let [{{:keys [x y]} :params} command]
       (gpt/point x y)))))


(defn make-move-to [to]
  {:command :move-to
   :relative false
   :params {:x (:x to)
            :y (:y to)}})

(defn make-line-to [to]
  {:command :line-to
   :relative false
   :params {:x (:x to)
            :y (:y to)}})

(defn make-curve-params
  ([point]
   (make-curve-params point point point))

  ([point handler] (make-curve-params point handler point))

  ([point h1 h2]
   {:x (:x point)
    :y (:y point)
    :c1x (:x h1)
    :c1y (:y h1)
    :c2x (:x h2)
    :c2y (:y h2)}))

(defn update-curve-to
  [command h1 h2]
  (-> command
      (assoc :command :curve-to)
      (assoc-in [:params :c1x] (:x h1))
      (assoc-in [:params :c1y] (:y h1))
      (assoc-in [:params :c2x] (:x h2))
      (assoc-in [:params :c2y] (:y h2))))

(defn make-curve-to
  [to h1 h2]
  {:command :curve-to
   :relative false
   :params (make-curve-params to h1 h2)})

(defn update-handler
  [command prefix point]
  (let [[cox coy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])]
    (-> command
        (assoc-in [:params cox] (:x point))
        (assoc-in [:params coy] (:y point)))))

(defn apply-content-modifiers
  "Apply to content a map with point translations"
  [content modifiers]
  (letfn [(apply-to-index [content [index params]]
            (if (contains? content index)
              (cond-> content
                (and
                 (or (:c1x params) (:c1y params) (:c2x params) (:c2y params))
                 (= :line-to (get-in content [index :command])))

                (-> (assoc-in [index :command] :curve-to)
                    (assoc-in [index :params]
                              (make-curve-params
                               (get-in content [index :params])
                               (get-in content [(dec index) :params]))))

                (:x params) (update-in [index :params :x] + (:x params))
                (:y params) (update-in [index :params :y] + (:y params))

                (:c1x params) (update-in [index :params :c1x] + (:c1x params))
                (:c1y params) (update-in [index :params :c1y] + (:c1y params))

                (:c2x params) (update-in [index :params :c2x] + (:c2x params))
                (:c2y params) (update-in [index :params :c2y] + (:c2y params)))
              content))]
    (let [content (if (vector? content) content (into [] content))]
      (reduce apply-to-index content modifiers))))


(defn get-handler [{:keys [params] :as command} prefix]
  (let [cx (d/prefix-keyword prefix :x)
        cy (d/prefix-keyword prefix :y)]
    (when (and command
               (contains? params cx)
               (contains? params cy))
      (gpt/point (get params cx)
                 (get params cy)))))

(defn content->handlers
  "Retrieve a map where for every point will retrieve a list of
  the handlers that are associated with that point.
  point -> [[index, prefix]]"
  [content]
  (->> (d/with-prev content)
       (d/enumerate)
       (mapcat (fn [[index [cur-cmd pre-cmd]]]
                 (if (and pre-cmd (= :curve-to (:command cur-cmd)))
                   (let [cur-pos (command->point cur-cmd)
                         pre-pos (command->point pre-cmd)]
                     (-> [[pre-pos [index :c1]]
                          [cur-pos [index :c2]]]))
                   [])))

       (group-by first)
       (d/mapm #(mapv second %2))))

(defn point-indices
  [content point]
  (->> (d/enumerate content)
       (filter (fn [[_ cmd]] (= point (command->point cmd))))
       (mapv (fn [[index _]] index))))

(defn handler-indices
  "Return an index where the key is the positions and the values the handlers"
  [content point]
  (->> (d/with-prev content)
       (d/enumerate)
       (mapcat (fn [[index [cur-cmd pre-cmd]]]
                 (if (and (some? pre-cmd) (= :curve-to (:command cur-cmd)))
                   (let [cur-pos (command->point cur-cmd)
                         pre-pos (command->point pre-cmd)]
                     (cond-> []
                       (= pre-pos point) (conj [index :c1])
                       (= cur-pos point) (conj [index :c2])))
                   [])))))

(defn opposite-index
  "Calculates the opposite index given a prefix and an index"
  [content index prefix]

  (let [point (if (= prefix :c2)
                (command->point (nth content index))
                (command->point (nth content (dec index))))

        point->handlers (content->handlers content)

        handlers (->> point
                      (point->handlers )
                      (filter (fn [[ci cp]] (and (not= index ci) (not= prefix cp)) )))]

    (cond
      (= (count handlers) 1)
      (->> handlers first)

      (and (= :c1 prefix) (= (count content) index))
      [(dec index) :c2]

      :else nil)))


(defn get-commands
  "Returns the commands involving a point with its indices"
  [content point]
  (->> (d/enumerate content)
       (filterv (fn [[_ cmd]] (= (command->point cmd) point)))))


(defn prefix->coords [prefix]
  (case prefix
    :c1 [:c1x :c1y]
    :c2 [:c2x :c2y]
    nil))

(defn handler->point [content index prefix]
  (when (and (some? index)
             (some? prefix)
             (contains? content index))
    (let [[cx cy] (prefix->coords prefix)]
      (if (= :curve-to (get-in content [index :command]))
        (gpt/point (get-in content [index :params cx])
                   (get-in content [index :params cy]))

        (gpt/point (get-in content [index :params :x])
                   (get-in content [index :params :y]))))))

(defn handler->node [content index prefix]
  (if (= prefix :c1)
    (command->point (get content (dec index)))
    (command->point (get content index))))

